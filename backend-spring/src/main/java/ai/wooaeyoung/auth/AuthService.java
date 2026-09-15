package ai.wooaeyoung.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * 사용자 인증 (F-028). 원본 backend/auth.py 포트.
 * PBKDF2-HMAC-SHA256 (사용자별 salt, 210k iterations) + 세션 토큰(30일 만료).
 */
@Service
public class AuthService {

    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final long TOKEN_TTL_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String hashPassword(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8),
                    ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = f.generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String randomHex(int nBytes) {
        byte[] b = new byte[nBytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private String randomUrlSafeToken(int nBytes) {
        byte[] b = new byte[nBytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public Map<String, Object> register(String emailIn, String password, String displayNameIn) {
        String email = emailIn.strip().toLowerCase();
        if (!email.contains("@") || email.length() < 5) {
            throw new IllegalArgumentException("올바른 이메일을 입력하세요.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다.");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email);
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
        String salt = randomHex(16);
        String hash = hashPassword(password, salt);
        String displayName = (displayNameIn == null || displayNameIn.isBlank())
                ? email.split("@")[0] : displayNameIn;
        jdbc.update("INSERT INTO users(email, password_hash, password_salt, display_name, created_at) VALUES (?,?,?,?,?)",
                email, hash, salt, displayName, Instant.now().toString());
        Long userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, email);
        return Map.of("user_id", userId, "email", email, "display_name", displayName);
    }

    public Map<String, Object> login(String emailIn, String password) {
        String email = emailIn.strip().toLowerCase();
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT user_id, password_hash, password_salt, display_name FROM users WHERE email=?", email);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        String candidate = hashPassword(password, (String) row.get("password_salt"));
        if (!constantTimeEquals(candidate, (String) row.get("password_hash"))) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        String token = randomUrlSafeToken(32);
        Instant now = Instant.now();
        Instant expires = now.plus(TOKEN_TTL_DAYS, ChronoUnit.DAYS);
        Object userId = row.get("user_id");
        jdbc.update("INSERT INTO sessions(token, user_id, created_at, expires_at) VALUES (?,?,?,?)",
                token, userId, now.toString(), expires.toString());
        return Map.of(
                "token", token,
                "user", Map.of(
                        "user_id", userId,
                        "email", email,
                        "display_name", row.get("display_name")
                )
        );
    }

    public void logout(String token) {
        jdbc.update("DELETE FROM sessions WHERE token=?", token);
    }

    public Map<String, Object> userForToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT s.user_id AS user_id, s.expires_at AS expires_at, u.email AS email, u.display_name AS display_name
                    FROM sessions s JOIN users u ON u.user_id = s.user_id
                    WHERE s.token = ?
                    """, token);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
        Instant expires;
        try {
            expires = Instant.parse((String) row.get("expires_at"));
        } catch (Exception e) {
            expires = Instant.EPOCH;
        }
        if (expires.isBefore(Instant.now())) {
            jdbc.update("DELETE FROM sessions WHERE token=?", token);
            return null;
        }
        return Map.of(
                "user_id", row.get("user_id"),
                "email", row.get("email"),
                "display_name", row.get("display_name")
        );
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
