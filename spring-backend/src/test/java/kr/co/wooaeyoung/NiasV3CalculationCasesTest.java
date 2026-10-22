package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static kr.co.wooaeyoung.StandardProfileContractTest.row;
import static kr.co.wooaeyoung.UnverifiedStandardsAllowedTest.assertLine;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 계산명세서 v3 11장 검산 케이스 T1~T8(test 프로필, allow-unverified=true).
 * 허용 오차는 명세 표의 표시 자릿수 ±0.5단위(예: 618.58 → ±0.005, 1,577.1 → ±0.05).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NiasV3CalculationCasesTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    record Result(int status, JsonNode body) {}

    Result analyze(String species, double weightKg, double age, String unit, String adultSize) throws Exception {
        String body = "{\"profile\":{\"name\":\"검산\",\"species\":\"" + species + "\",\"weightKg\":" + weightKg
                + ",\"age\":{\"value\":" + age + ",\"unit\":\"" + unit + "\"}"
                + (adultSize == null ? "" : ",\"adultSize\":\"" + adultSize + "\"") + ",\"completeFeed\":false},\"items\":[],"
                + "\"manualItems\":[{\"name\":\"검산용\",\"type\":\"SUPPLEMENT\",\"servingAmount\":1,\"servingUnit\":\"TABLET\",\"dailyAmount\":1,"
                + "\"nutrients\":[{\"nutrientId\":\"ZINC\",\"amount\":1,\"unit\":\"MG\"}]}],\"standardProfile\":\"NIAS_V3\"}";
        MockHttpServletResponse response = mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        return new Result(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
    }

    JsonNode ok(Result result) {
        assertEquals(200, result.status(), result.body().toString());
        return result.body();
    }

    static double energy(JsonNode analysis) { return analysis.get("referenceEnergyKcal").asDouble(); }

    @Test @DisplayName("T1 성견 10 kg 36개월: E 618.58, 비타민 D·A·아연")
    void t1AdultDog() throws Exception {
        JsonNode t1 = ok(analyze("DOG", 10, 36, "MONTH", null));
        assertEquals("ADULT", t1.get("lifeStage").asText());
        assertEquals(618.58, energy(t1), 0.005);
        assertLine(t1, "VITAMIN_D", 77.32, 371.15, 494.86, 0.005);
        assertLine(t1, "VITAMIN_A", 773.2, 38661d, 61858d, 0.5);
        assertLine(t1, "ZINC", 11.13, 35.10, null, 0.005);
    }

    @Test @DisplayName("T2 성묘 4 kg 36개월: E 253.15, 비타민 A·요오드")
    void t2AdultCat() throws Exception {
        JsonNode t2 = ok(analyze("CAT", 4, 36, "MONTH", null));
        assertEquals("ADULT", t2.get("lifeStage").asText());
        assertEquals(253.15, energy(t2), 0.005);
        assertLine(t2, "VITAMIN_A", 210.9, 12658d, 25315d, 0.5);
        assertLine(t2, "IODINE", 0.0380, 0.2848, 0.5696, 0.00005);
    }

    @Test @DisplayName("T3 강아지 3 kg 12주 S: k 189.07, E 430.98, 칼슘 1.077 / 2.694")
    void t3SmallPuppy() throws Exception {
        JsonNode t3 = ok(analyze("DOG", 3, 12, "WEEK", "S"));
        assertEquals("GROWTH_EARLY", t3.get("lifeStage").asText());
        assertEquals(430.98, energy(t3), 0.005);
        // 하한(dog_early 2.5)·상한(dog_growth 6.25). 예상 성체 6.23 kg < 31.75 kg이라 대형견 칼슘 미적용.
        JsonNode calcium = row(t3, "CALCIUM");
        assertEquals(1.077, calcium.get("minimum").asDouble(), 0.0005);
        assertEquals(2.694, calcium.get("upper").asDouble(), 0.0005);
        assertFalse(t3.get("warnings").toString().contains("WEIGHT_CLIPPED"));
    }

    @Test @DisplayName("T4 강아지 18 kg 20주 XL: E 1,577.1, 칼슘 상한 7.097(대형견 4.5 적용)")
    void t4ExtraLargePuppy() throws Exception {
        JsonNode t4 = ok(analyze("DOG", 18, 20, "WEEK", "XL"));
        assertEquals("GROWTH_LATE", t4.get("lifeStage").asText());
        assertEquals(1577.1, energy(t4), 0.05);
        assertEquals(7.097, row(t4, "CALCIUM").get("upper").asDouble(), 0.0005);
        assertFalse(t4.get("warnings").toString().contains("WEIGHT_CLIPPED"));
    }

    @Test @DisplayName("T5 강아지 40 kg 20주 S: 예상 성체 59.7 → 7.0 절단·경고, 대형견 칼슘 미적용, E 2,602.2, 칼슘 상한 16.26")
    void t5ClippedPuppy() throws Exception {
        JsonNode t5 = ok(analyze("DOG", 40, 20, "WEEK", "S"));
        assertEquals("GROWTH_LATE", t5.get("lifeStage").asText());
        assertEquals(2602.2, energy(t5), 0.05);
        assertEquals(16.26, row(t5, "CALCIUM").get("upper").asDouble(), 0.005);
        JsonNode warnings = t5.get("warnings");
        assertTrue(contains(warnings, "WEIGHT_CLIPPED"), warnings.toString());
        assertTrue(contains(warnings, "체급 또는 체중을 확인하세요: 예상 성체 체중 59.67 kg이 S 범위(0~7 kg)를 벗어나 7 kg으로 절단했습니다."), warnings.toString());
    }

    @Test @DisplayName("T6 고양이 1.5 kg 3개월: 성장1, E 295.23, 비타민 A·메치오닌")
    void t6KittenStage1() throws Exception {
        JsonNode t6 = ok(analyze("CAT", 1.5, 3, "MONTH", null));
        assertEquals("CAT_S1", t6.get("lifeStage").asText());
        assertEquals(295.23, energy(t6), 0.005);
        JsonNode vitaminA = row(t6, "VITAMIN_A");
        assertEquals(14762, vitaminA.get("caution").asDouble(), 0.5);
        assertEquals(29523, vitaminA.get("upper").asDouble(), 0.5);
        // 메치오닌은 implemented=false라 분석 행이 없다. 성분 목록의 cat_growth 최대값으로 같은 식(5장)을 검산한다.
        JsonNode methionine = catalogItem("METHIONINE");
        assertNull(methionine.get("caution").get("cat").get("value").numberValue(), "메치오닌 고양이 예외 주의값 없음");
        double upper = energy(t6) / 1000 * methionine.get("maximum").get("cat_growth").get("value").asDouble();
        assertEquals(1.107, upper, 0.0005);
        assertEquals(0.554, upper * 0.5, 0.0005); // 고양이 주의 비율(cautionRatio.cat)
    }

    @Test @DisplayName("T7 고양이 0.3 kg 2주: 미지원(현재 Java 응답 422 유지)")
    void t7TooYoungKitten() throws Exception {
        Result t7 = analyze("CAT", 0.3, 2, "WEEK", null);
        assertEquals(422, t7.status());
        assertEquals("CALCULATION_INPUT_ERROR", t7.body().get("detail").get("code").asText());
        assertTrue(t7.body().get("detail").get("message").asText().contains("8주 미만"));
    }

    @Test @DisplayName("T8 강아지 3 kg 12주 체급 없음: 체급 선택 필요")
    void t8PuppyWithoutSizeClass() throws Exception {
        Result t8 = analyze("DOG", 3, 12, "WEEK", null);
        assertEquals(422, t8.status());
        assertEquals("CALCULATION_INPUT_ERROR", t8.body().get("detail").get("code").asText());
        assertTrue(t8.body().get("detail").get("message").asText().startsWith("체급 선택 필요"));
    }

    JsonNode catalogItem(String id) throws Exception {
        String content = mvc.perform(get("/api/v1/nutrients").param("standardProfile", "NIAS_V3"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode item : mapper.readTree(content).get("items")) if (item.get("id").asText().equals(id)) return item;
        throw new AssertionError("성분 없음: " + id);
    }

    static boolean contains(JsonNode array, String text) {
        for (JsonNode item : array) if (item.asText().equals(text)) return true;
        return false;
    }
}
