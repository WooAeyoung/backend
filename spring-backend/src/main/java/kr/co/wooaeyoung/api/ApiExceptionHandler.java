package kr.co.wooaeyoung.api;

import kr.co.wooaeyoung.domain.AnalysisException;
import kr.co.wooaeyoung.domain.ExternalServiceUnavailableException;
import kr.co.wooaeyoung.domain.JarvisProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AnalysisException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> analysis(AnalysisException error) {
        return Map.of("detail", Map.of("code", "CALCULATION_INPUT_ERROR", "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream().findFirst()
                .map(field -> field.getField() + ": " + field.getDefaultMessage()).orElse("요청값을 확인해주세요.");
        return Map.of("detail", Map.of("code", "VALIDATION_ERROR", "message", message));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(Exception error) {
        return Map.of("detail", Map.of("code", "BAD_REQUEST", "message", "요청 형식 또는 값을 확인해 주세요."));
    }

    @ExceptionHandler(JarvisProviderException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> jarvisUnavailable(JarvisProviderException error) {
        return Map.of("detail", Map.of("code", "JARVIS_PROVIDER_UNAVAILABLE", "message", error.getMessage()));
    }

    @ExceptionHandler(ExternalServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> externalUnavailable(ExternalServiceUnavailableException error) {
        return Map.of("detail", Map.of("code", "EXTERNAL_SERVICE_UNAVAILABLE", "message", error.getMessage()));
    }
}
