package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.wooaeyoung.api.ApiModels;
import kr.co.wooaeyoung.api.ApiModels.AnalysisRequest;
import kr.co.wooaeyoung.api.ApiModels.RecommendationRequest;
import kr.co.wooaeyoung.api.ApiResponses;
import kr.co.wooaeyoung.api.ApiResponses.*;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/openapi.yaml과 응답·요청 record의 일치(Issue #5, BE-V3-014).
 * 스키마 이름 = Java 타입 이름(중첩 enum은 바깥 이름 + 이름, 예: ConversionStatus), properties 순서 = record 성분 순서,
 * required = @JsonInclude(NON_NULL)이 아닌 응답 성분, 타입·enum 값·$ref가 같아야 한다. 명세에만 있는 스키마도 허용하지 않는다.
 */
class OpenApiContractTest {
    private static final Path SPEC = Path.of("docs", "openapi.yaml");
    private static final String REF = "#/components/schemas/";
    private static final List<Class<?>> ROOTS = List.of(
            AnalysisResponse.class, RecommendationResponse.class, NutrientListResponse.class, NutrientCatalogResponse.class,
            ParameterCatalogResponse.class, ErrorResponse.class, AnalysisRequest.class, RecommendationRequest.class, StandardProfile.class);

    private static Map<String, Object> spec;
    private static Map<String, Map<String, Object>> schemas;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        try (Reader reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) { spec = new Yaml().load(reader); }
        schemas = (Map<String, Map<String, Object>>) ((Map<String, Object>) spec.get("components")).get("schemas");
    }

    @Test void everySchemaMatchesItsJavaTypeAndNoSchemaIsOrphaned() {
        Deque<Class<?>> queue = new ArrayDeque<>(ROOTS);
        Set<String> visited = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            String name = schemaName(type);
            if (!visited.add(name)) continue;
            Map<String, Object> schema = schemas.get(name);
            if (schema == null) { errors.add("스키마 없음: " + name); continue; }
            if (type.isEnum()) {
                List<String> constants = Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
                if (!"string".equals(schema.get("type")) || !constants.equals(schema.get("enum"))) errors.add(name + ".enum: " + constants + " ≠ " + schema.get("enum"));
                continue;
            }
            assertTrue(type.isRecord(), name + "은 record여야 함");
            RecordComponent[] components = type.getRecordComponents();
            @SuppressWarnings("unchecked") Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
            List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
            if (!names.equals(new ArrayList<>(properties.keySet()))) { errors.add(name + " properties: " + names + " ≠ " + properties.keySet()); continue; }
            if (type.getEnclosingClass() != ApiModels.class) {
                List<String> required = Arrays.stream(components).filter(component -> !omittable(component)).map(RecordComponent::getName).toList();
                Object declared = schema.getOrDefault("required", List.of());
                if (!required.equals(declared)) errors.add(name + ".required: " + required + " ≠ " + declared);
            }
            for (RecordComponent component : components)
                check(name + "." + component.getName(), component.getGenericType(), properties.get(component.getName()), queue, errors);
        }
        Set<String> orphans = new TreeSet<>(schemas.keySet());
        orphans.removeAll(visited);
        if (!orphans.isEmpty()) errors.add("Java 타입이 없는 스키마: " + orphans);
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test @SuppressWarnings("unchecked") void pathsDocumentTheContractEndpoints() {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertEquals(List.of("/api/v1/analyses", "/api/v1/recommendations", "/api/v1/nutrients", "/api/v2/standards/{profile}", "/api/v2/parameters/{profile}"),
                new ArrayList<>(paths.keySet()));
    }

    private static boolean omittable(RecordComponent component) {
        // JsonInclude는 RECORD_COMPONENT 대상이 아니라 접근자 메서드로 전파된다.
        JsonInclude include = component.getAccessor().getAnnotation(JsonInclude.class);
        return include != null && include.value() == JsonInclude.Include.NON_NULL;
    }

    @SuppressWarnings("unchecked")
    private static void check(String where, Type type, Map<String, Object> property, Deque<Class<?>> queue, List<String> errors) {
        if (property == null) { errors.add(where + ": 명세 없음"); return; }
        Class<?> raw = raw(type);
        if (Boolean.TRUE.equals(property.get("nullable")) && raw.isPrimitive()) errors.add(where + ": 기본형인데 nullable");
        // nullable $ref는 OpenAPI 3.0 관례대로 allOf 한 개로 감싼다.
        if (property.get("allOf") instanceof List<?> allOf && allOf.size() == 1) property = (Map<String, Object>) allOf.get(0);
        if (type instanceof TypeVariable<?> variable) { check(where, variable.getBounds()[0], property, queue, errors); return; }
        if (raw.isInterface() && raw.isSealed()) {
            List<String> expected = Arrays.stream(raw.getPermittedSubclasses()).map(sub -> REF + schemaName(sub)).toList();
            List<String> actual = property.get("oneOf") instanceof List<?> oneOf ? oneOf.stream().map(ref -> String.valueOf(((Map<String, Object>) ref).get("$ref"))).toList() : List.of();
            if (!expected.equals(actual)) errors.add(where + ".oneOf: " + expected + " ≠ " + actual);
            queue.addAll(List.of(raw.getPermittedSubclasses()));
            return;
        }
        if (raw.isEnum() || raw.isRecord()) {
            if (!(REF + schemaName(raw)).equals(property.get("$ref"))) errors.add(where + ": $ref " + REF + schemaName(raw) + " ≠ " + property.get("$ref"));
            queue.add(raw);
            return;
        }
        if (List.class.isAssignableFrom(raw)) {
            expectType(where, "array", property, errors);
            check(where + "[]", ((ParameterizedType) type).getActualTypeArguments()[0], (Map<String, Object>) property.get("items"), queue, errors);
            return;
        }
        if (Map.class.isAssignableFrom(raw)) {
            expectType(where, "object", property, errors);
            check(where + "{}", ((ParameterizedType) type).getActualTypeArguments()[1], (Map<String, Object>) property.get("additionalProperties"), queue, errors);
            return;
        }
        if (raw == LocalDate.class) {
            expectType(where, "string", property, errors);
            if (!"date".equals(property.get("format"))) errors.add(where + ": format date 아님");
            return;
        }
        expectType(where, scalar(raw, where), property, errors);
    }

    private static void expectType(String where, String expected, Map<String, Object> property, List<String> errors) {
        if (!expected.equals(property.get("type"))) errors.add(where + ": type " + expected + " ≠ " + property.get("type"));
    }

    private static String scalar(Class<?> raw, String where) {
        if (raw == String.class) return "string";
        if (raw == boolean.class || raw == Boolean.class) return "boolean";
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) return "integer";
        if (raw == double.class || raw == Double.class) return "number";
        throw new IllegalStateException(where + ": 대응하는 OpenAPI 타입이 없음 " + raw);
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (type instanceof TypeVariable<?> v) return raw(v.getBounds()[0]);
        throw new IllegalStateException("지원하지 않는 타입: " + type);
    }

    /** 응답·요청 모음 클래스의 중첩 타입은 그 이름, 다른 타입의 중첩 enum은 바깥 이름을 붙인다(Conversion.Status → ConversionStatus). */
    static String schemaName(Class<?> type) {
        Class<?> outer = type.getEnclosingClass();
        return outer == null || outer == ApiResponses.class || outer == ApiModels.class ? type.getSimpleName() : outer.getSimpleName() + type.getSimpleName();
    }
}
