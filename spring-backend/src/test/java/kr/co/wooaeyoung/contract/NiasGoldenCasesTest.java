package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 계산명세서 v3 11장 T1~T16 전체 목록(Issue #5, BE-V3-017). 정답과 담당 테스트는 contract/nias-t1-t16.json 한 곳에 둔다.
 * <ul>
 *   <li>T1~T12, T14~T16은 Issue #2~#4의 기존 테스트가 이미 검사하므로 중복으로 만들지 않고, 표의 coveredBy가 실제 테스트 메서드를
 *       가리키는지만 확인한다(이름이 바뀌거나 지워지면 실패).</li>
 *   <li>T13만 API 수준 테스트가 없어 여기서 새로 검사한다.</li>
 *   <li>참조 구현과 다르게 정한 부분(T7 422, T11 비타민 K, T14·T15 Ca:P)은 표의 decision에 적는다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NiasGoldenCasesTest {
    private static final ObjectMapper PLAIN = new ObjectMapper();
    private static final Pattern COVERED = Pattern.compile("^([\\w.$]+)#(\\w+)(?:\\[([\\w-]+)])?$");

    @Autowired MockMvc mvc;

    static JsonNode table() throws Exception {
        return PLAIN.readTree(Files.readString(ResponseContractSnapshotTest.CONTRACT.resolve("nias-t1-t16.json"), StandardCharsets.UTF_8));
    }

    @Test void tableListsT1ToT16OnceEachWithReferenceAnswer() throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : table().get("cases")) {
            ids.add(c.get("id").asText());
            assertFalse(c.get("reference").asText().isBlank(), c.get("id") + " 참조 구현 정답");
            assertTrue(c.get("coveredBy").size() > 0, c.get("id") + " 담당 테스트");
        }
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 16; i++) expected.add("T" + i);
        assertEquals(expected, ids);
    }

    @Test void everyCaseIsCoveredByAnExistingTestMethod() throws Exception {
        Set<String> errorCases = new HashSet<>();
        for (JsonNode c : PLAIN.readTree(Files.readString(ResponseContractSnapshotTest.CONTRACT.resolve("errors.json"), StandardCharsets.UTF_8)))
            errorCases.add(c.get("id").asText());
        List<String> errors = new ArrayList<>();
        for (JsonNode c : table().get("cases"))
            for (JsonNode covered : c.get("coveredBy")) {
                Matcher matcher = COVERED.matcher(covered.asText());
                if (!matcher.matches()) { errors.add(c.get("id") + ": 형식 오류 " + covered); continue; }
                try {
                    Method method = Class.forName(matcher.group(1)).getDeclaredMethod(matcher.group(2));
                    if (!method.isAnnotationPresent(Test.class) && !method.isAnnotationPresent(TestFactory.class) && !method.isAnnotationPresent(ParameterizedTest.class))
                        errors.add(c.get("id") + ": 테스트 메서드가 아님 " + covered);
                } catch (ClassNotFoundException | NoSuchMethodException error) {
                    errors.add(c.get("id") + ": 없음 " + covered);
                }
                if (matcher.group(3) != null && !errorCases.contains(matcher.group(3))) errors.add(c.get("id") + ": errors.json에 없는 케이스 " + matcher.group(3));
            }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test @DisplayName("T13 T1 성견의 비타민 E: 상한·주의선 null → total ≥ 하한이면 적정(상한 기준 없음)")
    void t13VitaminEAdultDogHasNoUpperOrCaution() throws Exception {
        // 참조 구현 T13과 같은 입력: T1 + complete_feed(사료 몫은 하한 × 사료 가정 배수로 추정).
        JsonNode estimated = vitaminE("\"items\":[{\"productId\":\"feed-complete-unknown\",\"dailyAmount\":150,\"unit\":\"G\"}]", true);
        assertTrue(estimated.get("upper").isNull(), "upper None");
        assertTrue(estimated.get("caution").isNull(), "caution None");
        assertEquals(5.567, estimated.get("minimum").asDouble(), 0.0005, "하한(명세 T15의 5.57 IU)");
        assertEquals("ESTIMATED", estimated.get("source").asText());
        assertTrue(estimated.get("total").asDouble() >= estimated.get("minimum").asDouble());
        assertEquals("ADEQUATE_NO_UPPER_LIMIT", estimated.get("status").asText(), "적정(상한 기준 없음), 기준 없음(NO_STANDARD)이 아님");

        // 실제 섭취량으로도 같은 판정 순서를 확인한다: 하한 이상이면 적정(상한 기준 없음), 하한 미만이면 부족.
        JsonNode adequate = vitaminE("\"items\":[],\"manualItems\":[" + manualVitaminE(10) + "]", false);
        assertEquals(10.0, adequate.get("total").asDouble(), 1e-12);
        assertEquals("ADEQUATE_NO_UPPER_LIMIT", adequate.get("status").asText());
        JsonNode deficient = vitaminE("\"items\":[],\"manualItems\":[" + manualVitaminE(1) + "]", false);
        assertEquals("DEFICIENT", deficient.get("status").asText());
    }

    private JsonNode vitaminE(String items, boolean completeFeed) throws Exception {
        String body = "{\"profile\":{\"name\":\"T13\",\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":36,\"unit\":\"MONTH\"},\"completeFeed\":" + completeFeed + "},"
                + items + ",\"standardProfile\":\"NIAS_V3\"}";
        JsonNode analysis = PLAIN.readTree(mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("ADULT", analysis.get("lifeStage").asText());
        assertEquals(618.58, analysis.get("referenceEnergyKcal").asDouble(), 0.005, "T1 기준 열량");
        for (JsonNode row : analysis.get("nutrients")) if (row.get("nutrientId").asText().equals("VITAMIN_E")) return row;
        throw new AssertionError("VITAMIN_E 행 없음");
    }

    private static String manualVitaminE(double iu) {
        return "{\"name\":\"비타민 E제\",\"type\":\"SUPPLEMENT\",\"servingAmount\":1,\"servingUnit\":\"TABLET\",\"dailyAmount\":1,"
                + "\"nutrients\":[{\"nutrientId\":\"VITAMIN_E\",\"amount\":" + iu + ",\"unit\":\"IU\"}]}";
    }
}
