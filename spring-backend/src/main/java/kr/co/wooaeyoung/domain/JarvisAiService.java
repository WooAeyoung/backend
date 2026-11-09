package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.*;

/** Provider-isolated JARVIS completion client. Secrets are read only from the server environment. */
@Service
public class JarvisAiService {
    private static final Set<String> GROK_MODELS = Set.of("grok-4.7", "grok-4-1-fast-reasoning", "grok-4-1-fast-non-reasoning", "grok-3-mini");
    private static final Set<String> OLLAMA_MODELS = Set.of("llama3.2", "qwen2.5:7b", "gemma3:4b");
    private static final Set<String> AIMLAPI_MODELS = Set.of("openai/gpt-5-5");
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;

    public JarvisAiService() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(12));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    public Optional<String> complete(String message, Map<String, Object> analysis) { return complete(message, analysis, null); }
    public Optional<String> complete(String message, Map<String, Object> analysis, String requestedProvider) {
        String provider = System.getenv().getOrDefault("JARVIS_PROVIDER", "grok").toLowerCase(Locale.ROOT);
        String fallback = System.getenv().getOrDefault("JARVIS_FALLBACK_PROVIDER", "").toLowerCase(Locale.ROOT);
        if ("grok".equals(provider) && (System.getenv("XAI_API_KEY") == null || System.getenv("XAI_API_KEY").isBlank())
                && !fallback.isBlank() && !fallback.equals(provider)) provider = fallback;
        try {
            Optional<String> answer = request(provider, message, analysis);
            if (answer.isPresent() && !answer.get().isBlank()) return answer;
            // No configured key or empty upstream response: command/data-based answers remain available.
            return Optional.empty();
        } catch (Exception primaryError) {
            if (!fallback.isBlank() && !fallback.equals(provider)) {
                try {
                    Optional<String> answer = request(fallback, message, analysis);
                    if (answer.isPresent() && !answer.get().isBlank()) return answer;
                } catch (Exception fallbackError) {
                    primaryError.addSuppressed(fallbackError);
                }
            }
            // Let JarvisService answer from the submitted analysis instead of failing the whole request.
            return Optional.empty();
        }
    }

    private Optional<String> request(String provider, String message, Map<String, Object> analysis) throws Exception {
        return switch (provider) {
            case "grok" -> grok(message, analysis);
            case "ollama" -> ollama(message, analysis);
            case "aimlapi" -> aimlapi(message, analysis);
            default -> throw new IllegalStateException("지원하지 않는 JARVIS 제공자입니다.");
        };
    }

    private Optional<String> grok(String message, Map<String, Object> analysis) throws Exception {
        String key = System.getenv("XAI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("GROK_MODEL", "grok-3-mini"), GROK_MODELS);
        Map<String, Object> body = Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", prompt(message, analysis))));
        String raw = client.post().uri("https://api.x.ai/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + key).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode text = mapper.readTree(raw).path("choices").path(0).path("message").path("content");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private Optional<String> ollama(String message, Map<String, Object> analysis) throws Exception {
        String model = allowed(System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2"), OLLAMA_MODELS);
        String base = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://127.0.0.1:11434");
        Map<String, Object> body = Map.of("model", model, "stream", false, "messages", List.of(Map.of("role", "user", "content", prompt(message, analysis))));
        String raw = client.post().uri(base + "/api/chat").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode text = mapper.readTree(raw).path("message").path("content");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private Optional<String> aimlapi(String message, Map<String, Object> analysis) throws Exception {
        String key = System.getenv("AIMLAPI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("AIMLAPI_MODEL", "openai/gpt-5-5"), AIMLAPI_MODELS);
        Map<String, Object> body = Map.of("model", model, "messages", List.of(
                Map.of("role", "user", "content", prompt(message, analysis))));
        String raw = client.post().uri("https://api.aimlapi.com/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode text = mapper.readTree(raw).path("choices").path(0).path("message").path("content");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private String allowed(String model, Set<String> allowlist) { if (!allowlist.contains(model)) throw new IllegalArgumentException("허용되지 않은 JARVIS 모델입니다."); return model; }
    private String providerErrorMessage(RestClientResponseException error) {
        String detail = "API 키와 모델 설정을 확인해 주세요.";
        try {
            JsonNode root = mapper.readTree(error.getResponseBodyAsString());
            JsonNode message = root.path("error").path("message");
            if (!message.isTextual() && root.path("error").isTextual()) message = root.path("error");
            if (!message.isTextual()) message = root.path("message");
            if (!message.isTextual()) message = root.path("detail");
            if (message.isTextual() && !message.asText().isBlank()) {
                detail = message.asText().replaceAll("[\\r\\n]+", " ");
                if (detail.length() > 240) detail = detail.substring(0, 240);
            }
        } catch (Exception ignored) {
            // Never expose raw upstream bodies, headers, or credentials.
        }
        return "AI 제공자 요청이 거부되었습니다 (HTTP " + error.getStatusCode().value() + "): " + detail;
    }
    private String prompt(String message, Map<String, Object> analysis) { return "우애영 반려동물 영양 도우미. 진단·처방을 단정하지 말고, 제공된 분석 데이터만 사용하세요. 분석 데이터가 없으면 추측하지 마세요. 페이지 이동은 명령 라우터가 처리합니다.\n분석: " + String.valueOf(analysis) + "\n사용자: " + message; }
}
