package kr.co.wooaeyoung.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Service
public class GitHubService {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String token;

    public GitHubService(ObjectMapper mapper, @Value("${app.github-token:}") String token) {
        this.mapper = mapper;
        this.token = token.strip();
    }

    public String createIssue(String repository, String title) {
        Map<String, Object> response = request("POST", "/repos/" + repository + "/issues",
                Map.of("title", title, "body", "카카오톡 우애영 봇에서 생성된 이슈입니다."));
        return String.valueOf(response.get("html_url"));
    }

    public void dispatchFrontendDeployment() {
        request("POST", "/repos/WooAeyoung/Frontend/actions/workflows/deploy-pages.yml/dispatches", Map.of("ref", "main"));
    }

    private Map<String, Object> request(String method, String path, Map<String, Object> body) {
        if (token.isBlank()) throw new IntegrationException("GitHub 토큰이 설정되지 않았습니다.");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "wooaeyoung-kakao-bot-java")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IntegrationException("GitHub API 오류(" + response.statusCode() + "): " + response.body().substring(0, Math.min(200, response.body().length())));
            if (response.body().isBlank()) return Map.of();
            return mapper.readValue(response.body(), Map.class);
        } catch (JsonProcessingException error) {
            throw new IntegrationException("GitHub 요청 데이터를 처리하지 못했습니다.", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IntegrationException("GitHub 요청이 중단되었습니다.", error);
        } catch (Exception error) {
            if (error instanceof IntegrationException integration) throw integration;
            throw new IntegrationException("GitHub API에 연결하지 못했습니다.", error);
        }
    }
}
