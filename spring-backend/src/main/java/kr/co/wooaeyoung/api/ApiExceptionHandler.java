package kr.co.wooaeyoung.api;

import kr.co.wooaeyoung.domain.AnalysisException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

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
}
