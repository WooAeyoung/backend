package kr.co.wooaeyoung.standards;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/** 기준 매개변수 파일을 엄격하게 읽고 Bean Validation으로 검증한다. 실패하면 애플리케이션 시작이 실패한다. */
@Component
public class StandardParametersLoader {
    private final ObjectMapper mapper;
    private final Validator validator;

    public StandardParametersLoader(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.validator = validator;
    }

    public <T> T load(StandardProfile profile, Class<T> type) {
        return load("standards/" + profile.directory() + "/parameters.json", type);
    }

    public <T> T load(String location, Class<T> type) {
        T value;
        try (InputStream input = new ClassPathResource(location).getInputStream()) {
            value = mapper.readValue(input, type);
        } catch (IOException error) {
            throw new IllegalStateException("기준 매개변수 파일을 읽을 수 없습니다: " + location, error);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String detail = violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().collect(Collectors.joining(", "));
            throw new IllegalStateException("기준 매개변수 검증 실패(" + location + "): " + detail);
        }
        return value;
    }
}
