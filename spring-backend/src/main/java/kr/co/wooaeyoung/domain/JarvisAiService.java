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
    private static final Set<String> GEMINI_MODELS = Set.of("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash-lite");
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;

    public JarvisAiService() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    public Optional<String> complete(String message, Map<String, Object> analysis) { return complete(message, analysis, null); }
    public Optional<String> complete(String message, Map<String, Object> analysis, String requestedProvider) {
        String provider = System.getenv().getOrDefault("JARVIS_PROVIDER", "grok").toLowerCase(Locale.ROOT);
        String fallback = System.getenv().getOrDefault("JARVIS_FALLBACK_PROVIDER", "").toLowerCase(Locale.ROOT);
        if (!hasCredentials(provider) && !fallback.isBlank() && !fallback.equals(provider)) provider = fallback;
        try {
            Optional<String> answer = request(provider, message, analysis);
            if (answer.isPresent() && !answer.get().isBlank()) return answer;
            // No configured key or empty upstream response: command/data-based answers remain available.
            return Optional.empty();
        } catch (Exception primaryError) {
            // Let JarvisService answer from the submitted analysis instead of failing the whole request.
            return Optional.empty();
        }
    }

    private Optional<String> request(String provider, String message, Map<String, Object> analysis) throws Exception {
        return switch (provider) {
            case "grok" -> grok(message, analysis);
            case "ollama" -> ollama(message, analysis);
            case "aimlapi" -> aimlapi(message, analysis);
            case "gemini" -> gemini(message, analysis);
            default -> throw new IllegalStateException("지원하지 않는 JARVIS 제공자입니다.");
        };
    }

    private boolean hasCredentials(String provider) {
        return switch (provider) {
            case "grok" -> hasValue("XAI_API_KEY");
            case "aimlapi" -> hasValue("AIMLAPI_API_KEY");
            case "gemini" -> hasValue("GEMINI_API_KEY");
            case "ollama" -> true;
            default -> false;
        };
    }

    private boolean hasValue(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
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

    private Optional<String> gemini(String message, Map<String, Object> analysis) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash-lite"), GEMINI_MODELS);
        Map<String, Object> body = Map.of("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt(message, analysis))))));
        String raw = client.post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class);
        JsonNode parts = mapper.readTree(raw).path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) return Optional.empty();
        StringBuilder answer = new StringBuilder();
        for (JsonNode part : parts) if (part.path("text").isTextual()) answer.append(part.path("text").asText());
        return answer.isEmpty() ? Optional.empty() : Optional.of(answer.toString());
    }

    private String allowed(String model, Set<String> allowlist) { if (!allowlist.contains(model)) throw new IllegalArgumentException("허용되지 않은 JARVIS 모델입니다."); return model; }
    private String prompt(String message, Map<String, Object> analysis) { return "우애영 반려동물 영양 도우미. 진단·처방을 단정하지 말고, 제공된 분석 데이터만 사용하세요. 분석 데이터가 없으면 추측하지 마세요. 페이지 이동은 명령 라우터가 처리합니다.\n분석: " + String.valueOf(analysis) + "\n사용자: " + message; }
}
