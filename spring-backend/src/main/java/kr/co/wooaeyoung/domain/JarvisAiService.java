package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.api.ApiModels;
import org.springframework.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.*;

/** Provider-isolated JARVIS completion client. Secrets are read only from the server environment. */
@Service
public class JarvisAiService {
    private static final Logger log = LoggerFactory.getLogger(JarvisAiService.class);
    private static final Set<String> GROK_MODELS = Set.of("grok-4.7", "grok-4-1-fast-reasoning", "grok-4-1-fast-non-reasoning", "grok-3-mini");
    private static final Set<String> OLLAMA_MODELS = Set.of("llama3.2", "qwen2.5:7b", "gemma3:4b");
    private static final Set<String> AIMLAPI_MODELS = Set.of("openai/gpt-5-5");
    private static final List<String> GEMINI_MODELS = List.of(
            "gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.8-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash");
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;

    public JarvisAiService() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    public Optional<String> complete(String message, Map<String, Object> analysis) { return complete(message, analysis, null); }
    public Optional<String> complete(String message, Map<String, Object> analysis, String requestedProvider) {
        return complete(message, analysis, requestedProvider, null);
    }
    public Optional<String> complete(String message, Map<String, Object> analysis, String requestedProvider, List<ApiModels.JarvisMessage> history) {
        String provider = System.getenv().getOrDefault("JARVIS_PROVIDER", "grok").toLowerCase(Locale.ROOT);
        String fallback = System.getenv().getOrDefault("JARVIS_FALLBACK_PROVIDER", "").toLowerCase(Locale.ROOT);
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        providers.add(provider);
        if (!fallback.isBlank()) providers.add(fallback);
        for (String candidate : providers) {
            if (!hasCredentials(candidate)) continue;
            try {
                Optional<String> answer = request(candidate, message, analysis, history);
                if (answer.isPresent() && !answer.get().isBlank()) return answer;
            } catch (Exception error) {
                if (error instanceof RestClientResponseException responseError) {
                    log.warn("JARVIS provider {} rejected the request with HTTP {}: {}", candidate,
                            responseError.getStatusCode().value(), safeErrorDetail(responseError));
                } else {
                    log.warn("JARVIS provider {} failed: {}", candidate, error.getClass().getSimpleName());
                }
            }
        }
        // Let JarvisService answer from the submitted analysis instead of failing the whole request.
        return Optional.empty();
    }

    private Optional<String> request(String provider, String message, Map<String, Object> analysis, List<ApiModels.JarvisMessage> history) throws Exception {
        return switch (provider) {
            case "grok" -> grok(message, analysis, history);
            case "ollama" -> ollama(message, analysis, history);
            case "aimlapi" -> aimlapi(message, analysis, history);
            case "gemini" -> gemini(message, analysis, history);
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

    private String safeErrorDetail(RestClientResponseException error) {
        String detail = error.getResponseBodyAsString().replaceAll("[\\r\\n]+", " ");
        detail = detail.replaceAll("(?i)(api[_-]?key|authorization|bearer)[^,}]*", "$1=[redacted]");
        return detail.substring(0, Math.min(detail.length(), 300));
    }

    private Optional<String> grok(String message, Map<String, Object> analysis, List<ApiModels.JarvisMessage> history) throws Exception {
        String key = System.getenv("XAI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("GROK_MODEL", "grok-4.7"), GROK_MODELS);
        Map<String, Object> body = Map.of(
                "model", model,
                "reasoning_effort", "low",
                "messages", List.of(Map.of("role", "user", "content", prompt(message, analysis, history))));
        String raw = client.post().uri("https://api.x.ai/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + key).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode text = mapper.readTree(raw).path("choices").path(0).path("message").path("content");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private Optional<String> ollama(String message, Map<String, Object> analysis, List<ApiModels.JarvisMessage> history) throws Exception {
        String model = allowed(System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2"), OLLAMA_MODELS);
        String base = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://127.0.0.1:11434");
        Map<String, Object> body = Map.of("model", model, "stream", false, "messages", List.of(Map.of("role", "user", "content", prompt(message, analysis, history))));
        String raw = client.post().uri(base + "/api/chat").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode text = mapper.readTree(raw).path("message").path("content");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private Optional<String> aimlapi(String message, Map<String, Object> analysis, List<ApiModels.JarvisMessage> history) throws Exception {
        String key = System.getenv("AIMLAPI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("AIMLAPI_MODEL", "openai/gpt-5-5"), AIMLAPI_MODELS);
        Map<String, Object> body = Map.of("model", model, "messages", List.of(
                Map.of("role", "user", "content", prompt(message, analysis, history))));
        String raw = client.post().uri("https://api.aimlapi.com/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        JsonNode text = mapper.readTree(raw).path("choices").path(0).path("message").path("content");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    private Optional<String> gemini(String message, Map<String, Object> analysis, List<ApiModels.JarvisMessage> history) throws Exception {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) return Optional.empty();
        String model = allowed(System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.5-flash"), GEMINI_MODELS);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(model);
        candidates.addAll(GEMINI_MODELS);
        Map<String, Object> body = Map.of("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt(message, analysis, history))))));
        for (String candidate : candidates) {
            try {
                String raw = client.post()
                        .uri("https://generativelanguage.googleapis.com/v1beta/models/" + candidate + ":generateContent")
                        .header("x-goog-api-key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().body(String.class);
                JsonNode parts = mapper.readTree(raw).path("candidates").path(0).path("content").path("parts");
                if (!parts.isArray()) return Optional.empty();
                StringBuilder answer = new StringBuilder();
                for (JsonNode part : parts) if (part.path("text").isTextual()) answer.append(part.path("text").asText());
                return answer.isEmpty() ? Optional.empty() : Optional.of(answer.toString());
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() != 404) throw error;
                log.info("Gemini model {} is unavailable; trying the next allowed model", candidate);
            }
        }
        return Optional.empty();
    }

    private String allowed(String model, Collection<String> allowlist) { if (!allowlist.contains(model)) throw new IllegalArgumentException("허용되지 않은 JARVIS 모델입니다."); return model; }
    private String prompt(String message, Map<String, Object> analysis, List<ApiModels.JarvisMessage> history) {
        StringBuilder conversation = new StringBuilder();
        if (history != null) for (ApiModels.JarvisMessage item : history) {
            if (item == null || item.content() == null) continue;
            conversation.append("assistant".equalsIgnoreCase(item.role()) ? "자비스: " : "사용자: ")
                    .append(item.content()).append('\n');
        }
        return "우애영 반려동물 영양 도우미입니다. 사용자의 현재 질문에 자연스럽게 답하고, 앞선 대화가 있으면 문맥을 이어가세요. 진단·처방을 단정하지 말고 분석 수치는 제공된 데이터만 사용하세요. 분석 데이터가 없어도 인사와 일반적인 이유 설명에는 답할 수 있습니다. 페이지 이동은 명령 라우터가 처리합니다.\n분석: " + String.valueOf(analysis) + "\n최근 대화:\n" + conversation + "사용자: " + message;
    }
}
