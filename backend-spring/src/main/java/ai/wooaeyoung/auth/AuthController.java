package ai.wooaeyoung.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** /api/auth/register, /login, /logout, /me — 원본 api.py 인증 엔드포인트(F-028) 포트. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record RegisterRequest(@NotBlank String email, @NotBlank String password, String displayName) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest req) {
        return auth.register(req.email(), req.password(), req.displayName());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        auth.logout(BearerToken.from(authHeader));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> user = auth.userForToken(BearerToken.from(authHeader));
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(user);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }
}
