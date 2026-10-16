package kr.co.wooaeyoung.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * FEDIAF_2025 운영 결과 회귀 방지용 골든 테스트.
 * 골든 파일은 리팩터링 전 Java 출력으로 만들었다. 다시 만들려면 -Dgolden.update=true로 실행한다.
 * 파일 형식: {"status": HTTP 상태 코드, "body": 응답 본문}.
 * 비교 규칙: 상태 코드가 같고, 골든 본문의 모든 필드가 이름·값 그대로 있어야 한다. 새로 추가된 필드는 무시한다.
 * traceId는 위치와 관계없이 랜덤 접미사가 있어 비어 있지 않은 문자열인지만 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FediafGoldenTest {
    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "golden", "fediaf");
    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final String TRACE_ID = "traceId";
    /** 의도적으로 오류 응답을 검사하는 케이스와 그 기대 상태 코드. 나머지 케이스는 모두 200이어야 한다. */
    private static final Map<String, Integer> EXPECTED_ERROR_STATUS = Map.of();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    static Stream<String> cases() {
        return CASES.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void responseMatchesGolden(String name) throws Exception {
        MockHttpServletResponse response = mvc.perform(CASES.get(name)).andReturn().getResponse();
        int status = response.getStatus();
        JsonNode body = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        Path file = GOLDEN_DIR.resolve(name + ".json");
        if (UPDATE) {
            int expectedStatus = EXPECTED_ERROR_STATUS.getOrDefault(name, 200);
            assertEquals(expectedStatus, status, name + " 골든 생성 실패: 기대 상태 코드가 아님. 본문: " + body);
            ObjectNode golden = mapper.createObjectNode();
            golden.put("status", status);
            golden.set("body", maskTraceIds(body.deepCopy()));
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(golden) + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(file), "골든 파일이 없습니다: " + file);
        JsonNode golden = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(golden.get("status").asInt(), status, name + " 상태 코드 불일치. 본문: " + body);
        List<String> differences = new ArrayList<>();
        compare("$", golden.get("body"), body, differences);
        assertEquals(List.of(), differences, name + " 골든 불일치");
    }

    private static JsonNode maskTraceIds(JsonNode node) {
        if (node instanceof ObjectNode object && object.has(TRACE_ID)) object.put(TRACE_ID, "<masked>");
        node.forEach(FediafGoldenTest::maskTraceIds);
        return node;
    }

    private static void compare(String path, JsonNode expected, JsonNode actual, List<String> differences) {
        if (actual == null || actual.isMissingNode()) { differences.add(path + ": 필드 없음"); return; }
        if (path.endsWith("." + TRACE_ID)) {
            if (!actual.isTextual() || actual.asText().isBlank()) differences.add(path + ": traceId 없음");
            return;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) { differences.add(path + ": 객체가 아님"); return; }
            for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                compare(path + "." + field.getKey(), field.getValue(), actual.get(field.getKey()), differences);
            }
        } else if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) { differences.add(path + ": 배열 길이 다름"); return; }
            for (int i = 0; i < expected.size(); i++) compare(path + "[" + i + "]", expected.get(i), actual.get(i), differences);
        } else if (!expected.equals(actual)) {
            differences.add(path + ": 기대 " + expected + ", 실제 " + actual);
        }
    }

    private static RequestBuilder analysis(String json) { return post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(json); }
    private static RequestBuilder recommendation(String json) { return post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(json); }

    private static String profile(String species, double weightKg, double age, String unit, Double expectedAdultWeightKg, boolean completeFeed) {
        return "{\"name\":\"골든\",\"species\":\"" + species + "\",\"weightKg\":" + weightKg
                + ",\"age\":{\"value\":" + age + ",\"unit\":\"" + unit + "\"}"
                + (expectedAdultWeightKg == null ? "" : ",\"expectedAdultWeightKg\":" + expectedAdultWeightKg)
                + ",\"completeFeed\":" + completeFeed + "}";
    }
    private static String item(String productId, double dailyAmount, String unit) {
        return "{\"productId\":\"" + productId + "\",\"dailyAmount\":" + dailyAmount + ",\"unit\":\"" + unit + "\"}";
    }
    private static String calciumPhosphorus(double calciumMg, double phosphorusMg) {
        return "{\"name\":\"칼슘인 테스트\",\"type\":\"SUPPLEMENT\",\"servingAmount\":1,\"servingUnit\":\"TABLET\",\"dailyAmount\":1,"
                + "\"nutrients\":[{\"nutrientId\":\"CALCIUM\",\"amount\":" + calciumMg + ",\"unit\":\"MG\"},"
                + "{\"nutrientId\":\"PHOSPHORUS\",\"amount\":" + phosphorusMg + ",\"unit\":\"MG\"}]}";
    }
    private static String request(String profile, List<String> items, List<String> manualItems) {
        return "{\"profile\":" + profile + ",\"items\":[" + String.join(",", items) + "],\"manualItems\":[" + String.join(",", manualItems) + "]}";
    }

    private static final Map<String, RequestBuilder> CASES = cases(Map.of(
            "adult-dog-10kg", request(profile("DOG", 10, 36, "MONTH", null, true),
                    List.of(item("feed-balanced-dog", 150, "G"), item("supp-multi", 1, "TABLET")), List.of()),
            "adult-cat-4kg", request(profile("CAT", 4, 36, "MONTH", null, true),
                    List.of(item("feed-balanced-cat", 60, "G")), List.of()),
            "growth-cat-3month", request(profile("CAT", 1.5, 3, "MONTH", null, true),
                    List.of(item("feed-balanced-cat", 40, "G")), List.of()),
            "growth-dog-early-10week", request(profile("DOG", 3, 10, "WEEK", 6d, true),
                    List.of(item("feed-balanced-dog", 90, "G")), List.of()),
            "growth-dog-late-large-16week", request(profile("DOG", 12, 16, "WEEK", 30d, true),
                    List.of(item("feed-balanced-dog", 300, "G"), item("supp-calcium", 1, "SCOOP")), List.of()),
            "growth-dog-late-small-6month", request(profile("DOG", 5, 6, "MONTH", 8d, true),
                    List.of(item("feed-balanced-dog", 120, "G")), List.of()),
            "missing-feed-complete", request(profile("DOG", 10, 36, "MONTH", null, true),
                    List.of(item("feed-complete-unknown", 150, "G")), List.of()),
            "missing-feed-not-complete", request(profile("DOG", 10, 36, "MONTH", null, false),
                    List.of(item("feed-complete-unknown", 150, "G")), List.of())));

    private static Map<String, RequestBuilder> cases(Map<String, String> requests) {
        Map<String, RequestBuilder> result = new HashMap<>();
        requests.forEach((name, json) -> {
            result.put("analysis-" + name, analysis(json));
            result.put("recommendation-" + name, recommendation(json));
        });
        // Ca:P 경계: 성견 하한 1.0·상한 2.0, 인 0, 후기 성장 대형견 6개월 미만 상한 1.6
        String adult = profile("DOG", 10, 36, "MONTH", null, false);
        result.put("analysis-ca-p-adult-exactly-1", analysis(request(adult, List.of(), List.of(calciumPhosphorus(1000, 1000)))));
        result.put("analysis-ca-p-adult-below-1", analysis(request(adult, List.of(), List.of(calciumPhosphorus(999, 1000)))));
        result.put("analysis-ca-p-adult-exactly-2", analysis(request(adult, List.of(), List.of(calciumPhosphorus(2000, 1000)))));
        result.put("analysis-ca-p-adult-above-2", analysis(request(adult, List.of(), List.of(calciumPhosphorus(2001, 1000)))));
        result.put("analysis-ca-p-phosphorus-zero", analysis(request(adult, List.of(), List.of(calciumPhosphorus(1000, 0)))));
        String largePuppy = profile("DOG", 12, 16, "WEEK", 30d, false);
        result.put("analysis-ca-p-large-puppy-1.7", analysis(request(largePuppy, List.of(), List.of(calciumPhosphorus(1700, 1000)))));
        result.put("recommendation-ca-p-adult-exactly-2", recommendation(request(adult, List.of(), List.of(calciumPhosphorus(2000, 1000)))));
        result.put("nutrients", get("/api/v1/nutrients"));
        result.put("health", get("/health"));
        return Map.copyOf(result);
    }
}
