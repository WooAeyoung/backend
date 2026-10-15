package kr.co.wooaeyoung.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KakaoBotService {
    private static final Map<String, String> REPOSITORIES = Map.of(
            "backend", "WooAeyoung/backend", "frontend", "WooAeyoung/Frontend");
    private final GitHubService github;
    private final Set<String> allowedUsers;
    private final String publicBaseUrl;
    private final String oauthBaseUrl;

    public KakaoBotService(GitHubService github,
            @Value("${app.kakao-allowed-user-ids:}") String allowedUsers,
            @Value("${app.public-base-url:https://wooaeyoung-backend.onrender.com}") String publicBaseUrl,
            @Value("${app.kakao-oauth-base-url:https://wooaeyoung-backend.onrender.com}") String oauthBaseUrl) {
        this.github = github;
        this.allowedUsers = new HashSet<>(Arrays.stream(allowedUsers.split(",")).map(String::strip).filter(value -> !value.isBlank()).toList());
        this.publicBaseUrl = publicBaseUrl.replaceAll("/$", "");
        this.oauthBaseUrl = oauthBaseUrl.replaceAll("/$", "");
    }

    public Map<String, Object> respond(Map<String, Object> payload) {
        Map<String, Object> userRequest = objectMap(payload.get("userRequest"));
        String utterance = String.valueOf(userRequest.getOrDefault("utterance", "")).strip();
        String normalized = utterance.toLowerCase(Locale.ROOT).replaceFirst("^/", "").replaceAll("\\s+", " ").strip();
        Map<String, Object> user = objectMap(userRequest.get("user"));
        String userId = String.valueOf(user.getOrDefault("id", "")).strip();
        String command = Map.of("help", "도움말", "헬프", "도움말", "메뉴", "도움말", "backend", "백엔드").getOrDefault(normalized, normalized);
        String message;
        if (command.startsWith("이슈 ")) message = createIssue(command, userId);
        else if (command.startsWith("배포 ")) message = deploy(command, userId);
        else message = staticMessage(command, userId);
        return response(message);
    }

    private String createIssue(String command, String userId) {
        String[] parts = command.split(" ", 3);
        if (parts.length < 3 || !REPOSITORIES.containsKey(parts[1])) return issueHelp();
        if (!allowedUsers.contains(userId)) return unauthorized();
        try { return "이슈를 만들었습니다: " + github.createIssue(REPOSITORIES.get(parts[1]), parts[2]); }
        catch (IntegrationException error) { return "이슈 생성에 실패했습니다. " + error.getMessage(); }
    }

    private String deploy(String command, String userId) {
        String[] parts = command.split(" ");
        if (parts.length != 2 || !REPOSITORIES.containsKey(parts[1])) return deployHelp();
        if (parts[1].equals("backend")) return "Backend는 Render 서비스라 GitHub Actions 배포 대상이 아닙니다. 현재는 /배포 frontend를 지원합니다.";
        if (!allowedUsers.contains(userId)) return unauthorized();
        try { github.dispatchFrontendDeployment(); return "Frontend GitHub Pages 배포를 시작했습니다."; }
        catch (IntegrationException error) { return "배포 실행에 실패했습니다. " + error.getMessage(); }
    }

    private String staticMessage(String command, String userId) {
        return switch (command) {
            case "도움말" -> "사용 가능한 명령어\n• /상태 - 연결 상태 확인\n• /이슈 - GitHub 이슈 명령 안내\n• /배포 - GitHub Actions 실행 안내\n• /로그인 - 카카오 인증 시작\n• /백엔드 - Backend 주소 보기\n• /프론트 - 프론트엔드 주소 보기\n• /내아이디 - 명령 권한 등록용 ID 확인";
            case "상태" -> "우애영 봇과 Java Spring Backend가 정상적으로 연결되어 있습니다.";
            case "이슈" -> issueHelp(); case "배포" -> deployHelp();
            case "프론트" -> "우애영 프론트엔드\n서비스: https://wooaeyoung.github.io/Frontend/\n저장소: https://github.com/WooAeyoung/Frontend";
            case "백엔드" -> "우애영 Java Backend\n상태 확인: " + publicBaseUrl + "/health\n저장소: https://github.com/WooAeyoung/backend";
            case "내아이디" -> "내 카카오 사용자 ID: " + (userId.isBlank() ? "확인할 수 없음" : userId);
            case "로그인" -> "카카오 인증을 시작하려면 아래 링크를 열어 주세요.\n" + oauthBaseUrl + "/api/v1/kakao/oauth/start";
            default -> "명령어를 알아듣지 못했어요. 아래 버튼을 누르거나 /도움말을 입력해 주세요.";
        };
    }

    private static String issueHelp() { return "이슈 명령 형식: /이슈 backend 제목 또는 /이슈 frontend 제목\n대상: WooAeyoung/backend, WooAeyoung/Frontend\nGitHub 토큰을 연결하면 실제 이슈 생성까지 실행할 수 있습니다."; }
    private static String deployHelp() { return "배포 명령 형식: /배포 backend 또는 /배포 frontend\nfrontend는 WooAeyoung/Frontend의 GitHub Pages 배포를 뜻합니다."; }
    private static String unauthorized() { return "실행 권한이 없습니다. /내아이디를 보내고 표시된 ID를 관리자에게 등록해 주세요."; }

    private static Map<String, Object> response(String message) {
        List<Map<String, String>> quickReplies = List.of(
                quick("도움말", "/도움말"), quick("연결 상태", "/상태"), quick("이슈 만들기", "/이슈"), quick("배포하기", "/배포"),
                quick("로그인", "/로그인"), quick("백엔드 열기", "/백엔드"), quick("프론트 열기", "/프론트"), quick("내 ID", "/내아이디"));
        return Map.of("version", "2.0", "template", Map.of(
                "outputs", List.of(Map.of("simpleText", Map.of("text", message))), "quickReplies", quickReplies));
    }
    private static Map<String, String> quick(String label, String text) { return Map.of("action", "message", "label", label, "messageText", text); }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
}
