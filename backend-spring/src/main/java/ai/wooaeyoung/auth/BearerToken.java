package ai.wooaeyoung.auth;

/** {@code Authorization: Bearer <token>} 헤더에서 토큰만 뽑아낸다. 여러 컨트롤러가 공유. */
public final class BearerToken {
    private BearerToken() {}

    public static String from(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring("Bearer ".length());
        }
        return authorizationHeader;
    }
}
