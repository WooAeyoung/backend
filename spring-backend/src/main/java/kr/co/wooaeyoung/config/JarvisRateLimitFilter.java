package kr.co.wooaeyoung.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JarvisRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_SECONDS = 60;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public JarvisRateLimitFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !"/api/v1/jarvis".equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = Instant.now().getEpochSecond();
        String key = clientKey(request);
        Window current = windows.compute(key, (ignored, old) -> old == null || now - old.startedAt >= WINDOW_SECONDS
                ? new Window(now, 1) : new Window(old.startedAt, old.count + 1));
        if (current.count > MAX_REQUESTS) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(Math.max(1, WINDOW_SECONDS - (now - current.startedAt))));
            mapper.writeValue(response.getWriter(), Map.of("detail", Map.of(
                    "code", "RATE_LIMITED", "message", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")));
            return;
        }
        chain.doFilter(request, response);
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_SECONDS);
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].strip();
    }

    private record Window(long startedAt, int count) {}
}
