package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.wooaeyoung.api.ApiModels.AnalysisRequest;
import kr.co.wooaeyoung.api.ApiModels.RecommendationRequest;
import kr.co.wooaeyoung.api.ResponseAssembler;
import kr.co.wooaeyoung.domain.NutritionService;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 응답 record 전환 전후 JSON 동일성(Issue #5, BE-V3-014). FEDIAF 골든과 별도다.
 *
 * <ul>
 *   <li>스냅숏: contract/requests.json의 요청을 전환 전 코드(3cd1049)로 실행한 응답 본문 그대로(tools/verify/capture_contract_snapshots.py).</li>
 *   <li>HTTP 응답은 스냅숏과 바이트 단위로 같아야 한다. traceId는 입력 해시(앞 16자)는 비교하고 UUID 접미사(8자)만 가린다.</li>
 *   <li>예외(결정 D4): 전환 전 Map.of로 만들어 JVM마다 키 순서가 달랐던 객체는 스냅숏 쪽을 record 선언 순서로 맞춘 뒤 비교한다.
 *       ratios.calciumPhosphorus(value, status), excluded[](productId, name, reason …), GET /api/v1/nutrients items[](id, name, unit).</li>
 *   <li>같은 호출의 Map 응답과 record 응답을 엄격 모드(모르는 키·null 기본형 거부)로 다시 대조해 record가 빠뜨린 키가 없는지 본다.</li>
 * </ul>
 */
class ResponseContractSnapshotTest {
    static final Path CONTRACT = Path.of("src", "test", "resources", "contract");
    private static final ObjectMapper PLAIN = new ObjectMapper();

    record Case(String id, String mode, String method, String path, JsonNode body) {}

    static List<Case> cases(String mode) {
        try {
            List<Case> result = new ArrayList<>();
            for (JsonNode node : PLAIN.readTree(Files.readString(CONTRACT.resolve("requests.json"), StandardCharsets.UTF_8)))
                if (node.get("mode").asText().equals(mode))
                    result.add(new Case(node.get("id").asText(), mode, node.get("method").asText(), node.get("path").asText(), node.get("body")));
            return result;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    static String snapshot(String id) throws IOException {
        return Files.readString(CONTRACT.resolve("snapshots").resolve(id + ".json"), StandardCharsets.UTF_8);
    }

    /** traceId = 입력 해시 16자 + "-" + UUID 8자. UUID 부분만 가린다. */
    static String maskTraceId(String json) {
        return json.replaceAll("(\"traceId\":\"[0-9a-f]{16}-)[0-9a-f]{8}\"", "$1********\"");
    }

    /** 결정 D4: 전환 전 키 순서가 JVM마다 달랐던 객체를 record 선언 순서로 맞춘다. 나머지 키는 원래 순서 그대로 뒤에 둔다. */
    static JsonNode fixMapOfKeyOrder(JsonNode root) {
        JsonNode copy = root.deepCopy();
        if (!(copy instanceof ObjectNode object)) return copy;
        if (object.get("ratios") instanceof ObjectNode ratios && ratios.get("calciumPhosphorus") instanceof ObjectNode ratio)
            ratios.set("calciumPhosphorus", reorder(ratio, List.of("value", "status")));
        if (object.get("excluded") instanceof ArrayNode excluded)
            for (int i = 0; i < excluded.size(); i++) excluded.set(i, reorder((ObjectNode) excluded.get(i), List.of("productId", "name", "reason")));
        if (object.size() == 1 && object.get("items") instanceof ArrayNode items)
            for (int i = 0; i < items.size(); i++)
                if (items.get(i) instanceof ObjectNode item && item.size() == 3 && item.has("id") && item.has("name") && item.has("unit"))
                    items.set(i, reorder(item, List.of("id", "name", "unit")));
        return copy;
    }

    private static ObjectNode reorder(ObjectNode node, List<String> first) {
        ObjectNode result = PLAIN.createObjectNode();
        for (String key : first) if (node.has(key)) result.set(key, node.get(key));
        node.fields().forEachRemaining(field -> { if (!result.has(field.getKey())) result.set(field.getKey(), field.getValue()); });
        return result;
    }

    @Test void snapshotsRoundTripThroughJacksonUnchanged() throws IOException {
        // 바이트 비교의 전제: 스냅숏을 트리로 읽어 다시 쓰면 원문과 같다(숫자 표기 포함).
        for (String mode : List.of("default", "strict"))
            for (Case c : cases(mode)) {
                String raw = snapshot(c.id());
                assertEquals(raw, PLAIN.writeValueAsString(PLAIN.readTree(raw)), c.id());
            }
    }

    @Test void requestsCoverEveryContractEndpointInBothModes() {
        for (String mode : List.of("default", "strict")) {
            Set<String> endpoints = new TreeSet<>();
            for (Case c : cases(mode)) endpoints.add(c.path().replaceAll("\\?.*", "").replaceAll("/(NIAS_V3|FEDIAF_2025)$", "/{profile}"));
            assertTrue(endpoints.containsAll(Set.of("/api/v1/analyses", "/api/v1/recommendations", "/api/v2/standards/{profile}", "/api/v2/parameters/{profile}")), mode + endpoints);
        }
    }

    /**
     * @JsonInclude(NON_NULL) 성분은 값이 null이면 키가 빠진다. 전환 전 응답에서 그 키가 null 값으로 나온 적이 있으면
     * 전환 후 키가 사라지는 것이므로, 스냅숏 어디에도 그런 키가 null로 없어야 한다(키가 아예 없는 것은 허용).
     */
    @Test void omittableFieldsNeverAppearAsNullInSnapshots() throws IOException {
        Set<String> omittable = new TreeSet<>();
        for (Class<?> type : kr.co.wooaeyoung.api.ApiResponses.class.getDeclaredClasses())
            if (type.isRecord())
                for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                    com.fasterxml.jackson.annotation.JsonInclude include = component.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonInclude.class);
                    if (include != null && include.value() == com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) omittable.add(type.getSimpleName() + "." + component.getName());
                }
        assertEquals(Set.of("VerificationSummary.insufficientData", "NutrientRow.provenance", "NutrientRow.unknownContribution",
                "Contribution.nutrientSources", "Ratios.calciumPhosphorus", "ExcludedProduct.reasonCode", "ExcludedProduct.reasons",
                "ExclusionReason.ratioStatus"), omittable);
        Set<String> keys = new TreeSet<>();
        omittable.forEach(name -> keys.add(name.substring(name.indexOf('.') + 1)));
        keys.remove("calciumPhosphorus");   // ratioApplicability.calciumPhosphorus(문자열)와 이름이 같아 아래에서 경로로 본다
        List<String> nullValued = new ArrayList<>();
        for (String mode : List.of("default", "strict"))
            for (Case c : cases(mode)) {
                JsonNode root = PLAIN.readTree(snapshot(c.id()));
                for (JsonNode found : root.findParents("provenance")) collectNull(c.id(), found, keys, nullValued);
                for (String key : keys) for (JsonNode parent : root.findParents(key)) collectNull(c.id(), parent, keys, nullValued);
                if (root.path("ratios").has("calciumPhosphorus") && root.path("ratios").get("calciumPhosphorus").isNull())
                    nullValued.add(c.id() + ": ratios.calciumPhosphorus");
            }
        assertTrue(nullValued.isEmpty(), "전환 후 빠질 null 키: " + nullValued);
    }

    private static void collectNull(String id, JsonNode parent, Set<String> keys, List<String> result) {
        for (String key : keys) if (parent.has(key) && parent.get(key).isNull()) result.add(id + ": " + key);
    }

    abstract static class Cases {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;
        @Autowired NutritionService nutrition;

        abstract String mode();

        @TestFactory Stream<DynamicTest> httpResponsesMatchPreConversionSnapshots() {
            return cases(mode()).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
                MockHttpServletResponse response = mvc.perform(c.method().equals("GET") ? get(c.path())
                        : post(c.path()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(c.body()))).andReturn().getResponse();
                assertEquals(200, response.getStatus(), c.id());
                String expected = PLAIN.writeValueAsString(fixMapOfKeyOrder(PLAIN.readTree(snapshot(c.id()))));
                assertEquals(maskTraceId(expected), maskTraceId(response.getContentAsString(StandardCharsets.UTF_8)), c.id());
            }));
        }

        @TestFactory Stream<DynamicTest> recordsCarryEveryMapKeyInStrictMode() {
            ObjectMapper strictMapper = mapper.copy()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
            ResponseAssembler strict = new ResponseAssembler(strictMapper);
            return cases(mode()).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
                Object map = serviceResult(c);
                Object record = toRecord(strict, c, map);
                String expected = mapper.writeValueAsString(fixMapOfKeyOrder(mapper.valueToTree(map)));
                assertEquals(expected, mapper.writeValueAsString(record), c.id());
            }));
        }

        private Object serviceResult(Case c) throws IOException {
            String path = c.path();
            if (path.equals("/api/v1/analyses")) return nutrition.analyze(mapper.treeToValue(c.body(), AnalysisRequest.class));
            if (path.equals("/api/v1/recommendations")) return nutrition.recommend(mapper.treeToValue(c.body(), RecommendationRequest.class));
            if (path.equals("/api/v1/nutrients")) return Map.of("items", nutrition.nutrients());   // ApiController와 같은 감싸기
            if (path.startsWith("/api/v1/nutrients?standardProfile=")) return nutrition.nutrientCatalog(profile(path));
            if (path.startsWith("/api/v2/standards/")) return nutrition.nutrientCatalog(profile(path));
            if (path.startsWith("/api/v2/parameters/")) return nutrition.parameterCatalog(profile(path));
            throw new IllegalArgumentException("알 수 없는 경로: " + path);
        }

        @SuppressWarnings("unchecked")
        private static Object toRecord(ResponseAssembler assembler, Case c, Object map) {
            String path = c.path();
            if (path.equals("/api/v1/analyses")) return assembler.analysis((Map<String, Object>) map);
            if (path.equals("/api/v1/recommendations")) return assembler.recommendation((Map<String, Object>) map);
            if (path.equals("/api/v1/nutrients")) return assembler.nutrients((List<Map<String, String>>) ((Map<String, Object>) map).get("items"));
            if (path.startsWith("/api/v2/parameters/")) return assembler.parameterCatalog((Map<String, Object>) map);
            return assembler.nutrientCatalog((Map<String, Object>) map);
        }

        private static StandardProfile profile(String path) { return StandardProfile.valueOf(path.replaceAll(".*[=/]", "")); }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class Default extends Cases {
        @Override String mode() { return "default"; }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "wooaeyoung.standards.allow-unverified=false")
    class Strict extends Cases {
        @Override String mode() { return "strict"; }
    }
}
