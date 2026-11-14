package kr.co.wooaeyoung.api;

import kr.co.wooaeyoung.api.ApiResponses.ErrorResponse;
import kr.co.wooaeyoung.domain.AnalysisException;
import kr.co.wooaeyoung.domain.ExternalServiceUnavailableException;
import kr.co.wooaeyoung.domain.JarvisProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** 오류 본문 {"detail":{"code","message"[,"reason"]}}. code·message·HTTP 상태는 그대로이고 reason만 더한다(BE-V3-015). */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AnalysisException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse analysis(AnalysisException error) {
        return ErrorResponse.of("CALCULATION_INPUT_ERROR", error.getMessage(), ErrorReason.ofCalculationMessage(error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream().findFirst()
                .map(field -> field.getField() + ": " + field.getDefaultMessage()).orElse("요청값을 확인해주세요.");
        return ErrorResponse.of("VALIDATION_ERROR", message, null);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse badRequest(Exception error) {
        return ErrorResponse.of("BAD_REQUEST", "요청 형식 또는 값을 확인해 주세요.", ErrorReason.ofBadRequest(error));
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
