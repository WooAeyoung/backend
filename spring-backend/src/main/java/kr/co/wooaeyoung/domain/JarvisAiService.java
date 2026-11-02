package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.*;

/** Provider-isolated JARVIS completion client. Secrets are read only from the server environment. */
@Service
public class JarvisAiService {
    private static final Set<String> GROK_MODELS = Set.of("grok-4-1-fast-reasoning", "grok-4-1-fast-non-reasoning", "grok-3-mini");
    private static final Set<String> OLLAMA_MODELS = Set.of("llama3.2", "qwen2.5:7b", "gemma3:4b");
    private static final Set<String> AIMLAPI_MODELS = Set.of("openai/gpt-5-5");
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;

    public JarvisAiService() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    public Optional<String> complete(String message, Map<String, Object> analysis) { return complete(message, analysis, null); }
    public Optional<String> complete(String message, Map<String, Object> analysis, String requestedProvider) {
        String provider = System.getenv().getOrDefault("JARVIS_PROVIDER", "grok").toLowerCase(Locale.ROOT);
        try {
            return switch (provider) {
                case "grok" -> grok(message, analysis);
                case "ollama" -> ollama(message, analysis);
                case "aimlapi" -> aimlapi(message, analysis);
                default -> throw new IllegalStateException("지원하지 않는 JARVIS_PROVIDER입니다.");
            };
        } catch (Exception error) {
            throw new JarvisProviderException("AI 제공자 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.", error);
        }
    }

    private Optional<String> grok(String message, Map<String, Object> analysis) throws Exception {
        String key = System.getenv("XAI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("GROK_MODEL", "grok-4-1-fast-non-reasoning"), GROK_MODELS);
        Map<String, Object> body = Map.of("model", model, "input", List.of(Map.of("role", "user", "content", prompt(message, analysis))));
        String raw = client.post().uri("https://api.x.ai/v1/responses").header(HttpHeaders.AUTHORIZATION, "Bearer " + key).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode root = mapper.readTree(raw);
        JsonNode output = root.path("output");
        for (JsonNode item : output) for (JsonNode content : item.path("content")) if (content.has("text")) return Optional.of(content.get("text").asText());
        return Optional.empty();
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
    private String prompt(String message, Map<String, Object> analysis) { return "우애영 반려동물 영양 도우미. 진단·처방을 단정하지 말고, 제공된 분석 데이터만 사용하세요. 분석 데이터가 없으면 추측하지 마세요. 페이지 이동은 명령 라우터가 처리합니다.\n분석: " + String.valueOf(analysis) + "\n사용자: " + message; }
}
