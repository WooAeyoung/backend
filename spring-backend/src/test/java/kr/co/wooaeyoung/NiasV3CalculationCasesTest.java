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
import java.util.List;
import java.util.Set;

import static kr.co.wooaeyoung.StandardProfileContractTest.fieldNames;
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

    // ---- energyBasis(계산 근거)와 대형견 칼슘 출처 (BE-V3-008)

    static void assertNumber(double expected, JsonNode node, String field, double tolerance) {
        assertTrue(node.get(field).isNumber(), field + " 숫자 아님: " + node);
        assertEquals(expected, node.get(field).asDouble(), tolerance, field);
    }

    @Test @DisplayName("T1·T6 energyBasis: 성견 계수 110, 고양이 성장1 배수 2.25")
    void energyBasisForAdultDogAndKitten() throws Exception {
        JsonNode t1 = ok(analyze("DOG", 10, 36, "MONTH", null)).get("energyBasis");
        assertEquals("ADULT", t1.get("lifeStage").asText());
        assertEquals("ADULT_K", t1.get("kSource").asText());
        assertNumber(110, t1, "k", 0); assertNumber(110, t1, "adultK", 0); assertNumber(0.75, t1, "exponent", 0);
        assertNumber(618.58, t1, "referenceEnergyKcal", 0.005);
        assertFalse(t1.get("blocked").asBoolean());
        assertTrue(t1.get("blockedReason").isNull() && t1.get("catGrowthMultiplier").isNull() && t1.get("puppyGrowth").isNull());
        assertEquals("PENDING_VERIFICATION", t1.get("parameterVerification").get("energy").asText());

        JsonNode t6 = ok(analyze("CAT", 1.5, 3, "MONTH", null)).get("energyBasis");
        assertEquals("CAT_S1", t6.get("lifeStage").asText());
        assertEquals("CAT_GROWTH_MULTIPLIER", t6.get("kSource").asText());
        assertNumber(100, t6, "adultK", 0); assertNumber(2.25, t6, "catGrowthMultiplier", 0);
        assertNumber(225, t6, "k", 1e-9); assertNumber(0.67, t6, "exponent", 0);
        assertNumber(295.23, t6, "referenceEnergyKcal", 0.005);
        assertTrue(t6.get("puppyGrowth").isNull());
    }

    @Test @DisplayName("T3 energyBasis: S 성장곡선 pct 48.17, 예상 성체 6.23, k 189.07, 대형견 칼슘 미적용")
    void energyBasisForSmallPuppy() throws Exception {
        JsonNode t3 = ok(analyze("DOG", 3, 12, "WEEK", "S"));
        JsonNode basis = t3.get("energyBasis");
        assertEquals("PUPPY_GROWTH_CURVE", basis.get("kSource").asText());
        assertNumber(189.07, basis, "k", 0.005);
        assertNumber(430.98, basis, "referenceEnergyKcal", 0.005);
        assertTrue(basis.get("adultK").isNull());
        assertEquals(Set.of("energy", "growthCurve", "largeBreedCalcium"), fieldNames(basis.get("parameterVerification")));
        JsonNode growth = basis.get("puppyGrowth");
        assertEquals("S", growth.get("sizeClass").asText());
        assertNumber(36.92, growth, "a", 0); assertNumber(43.57, growth, "b", 0); assertNumber(12, growth, "ageWeeks", 0);
        assertNumber(48.17, growth, "percentOfAdultWeight", 0.005);
        assertNumber(6.23, growth, "expectedAdultWeightRawKg", 0.005);
        assertNumber(6.23, growth, "expectedAdultWeightKg", 0.005);
        assertFalse(growth.get("clipped").asBoolean());
        assertNumber(0, growth.get("sizeRange"), "lowerKg", 0); assertNumber(7, growth.get("sizeRange"), "upperKg", 0);
        assertNumber(254.1, growth, "puppyIntercept", 0); assertNumber(135, growth, "puppySlope", 0);
        assertFalse(growth.get("largeBreedCalcium").get("applied").asBoolean());
        assertNumber(31.75, growth.get("largeBreedCalcium"), "thresholdKg", 0);
        // 대형견 예외가 아니면 칼슘 상한 출처는 원래 dog_growth 값(표 2-17c)
        assertEquals("2-17c", row(t3, "CALCIUM").get("reference").get("upper").get("table").asText());
    }

    @Test @DisplayName("T4 energyBasis·출처: XL 예상 성체 33.00 ≥ 31.75 → 칼슘 최대 4.5(표 2-17c 각주 d)")
    void largeBreedCalciumForExtraLargePuppy() throws Exception {
        JsonNode t4 = ok(analyze("DOG", 18, 20, "WEEK", "XL"));
        JsonNode growth = t4.get("energyBasis").get("puppyGrowth");
        assertNumber(54.54, growth, "percentOfAdultWeight", 0.005);
        assertNumber(33.00, growth, "expectedAdultWeightKg", 0.005);
        assertNumber(180.47, t4.get("energyBasis"), "k", 0.005);
        assertFalse(growth.get("clipped").asBoolean());
        assertTrue(growth.get("largeBreedCalcium").get("applied").asBoolean());
        JsonNode calcium = row(t4, "CALCIUM");
        assertEquals(7.097, calcium.get("upper").asDouble(), 0.0005);
        assertEquals(7.097 * 0.75, calcium.get("caution").asDouble(), 0.0005); // 예외 주의값 없음 → 상한 × 개 주의 비율
        JsonNode upper = calcium.get("reference").get("upper");
        assertEquals("2-17c 각주 d", upper.get("table").asText());
        assertEquals("PENDING_VERIFICATION", upper.get("verificationStatus").asText());
        assertEquals("LITERATURE", upper.get("valueType").asText());
        assertTrue(upper.get("note").asText().contains("6.25 대신 4.5"), upper.toString());
        assertTrue(upper.get("note").asText().contains("33 kg ≥ 31.75 kg"), upper.toString());
    }

    @Test @DisplayName("T5 energyBasis: 원값 59.7 → 7.0 절단, clipped, 대형견 칼슘 미적용")
    void clippedPuppyBasis() throws Exception {
        JsonNode t5 = ok(analyze("DOG", 40, 20, "WEEK", "S"));
        JsonNode growth = t5.get("energyBasis").get("puppyGrowth");
        assertNumber(59.7, growth, "expectedAdultWeightRawKg", 0.05);
        assertNumber(7.0, growth, "expectedAdultWeightKg", 0.05);
        assertTrue(growth.get("clipped").asBoolean());
        assertNumber(7, growth.get("sizeRange"), "upperKg", 0);
        assertFalse(growth.get("largeBreedCalcium").get("applied").asBoolean());
        assertEquals("2-17c", row(t5, "CALCIUM").get("reference").get("upper").get("table").asText());
    }

    @Test @DisplayName("XXL은 하한만 적용: 상한 null, 하한 미만이면 하한으로 절단")
    void extraExtraLargeHasNoUpperBound() throws Exception {
        JsonNode small = ok(analyze("DOG", 5, 20, "WEEK", "XXL"));
        JsonNode growth = small.get("energyBasis").get("puppyGrowth");
        assertTrue(growth.get("sizeRange").get("upperKg").isNull());
        assertTrue(growth.get("clipped").asBoolean());
        assertNumber(47.5, growth, "expectedAdultWeightKg", 0);
        assertTrue(contains(small.get("warnings"), "WEIGHT_CLIPPED"));
        assertTrue(small.get("warnings").toString().contains("XXL 범위(47.5 kg 초과)"), small.get("warnings").toString());
        JsonNode large = ok(analyze("DOG", 60, 40, "WEEK", "XXL")).get("energyBasis").get("puppyGrowth");
        assertFalse(large.get("clipped").asBoolean());
        assertTrue(large.get("expectedAdultWeightKg").asDouble() > 47.5);
    }

    // ---- 성장기 기준선 (BE-V3-008): 주의선만 있는 성분도 기준 없음이 아니다

    @Test @DisplayName("성장기 기준선: 세 선이 모두 null일 때만 NO_STANDARD, 주의선만 있는 성분은 판정")
    void growthStageThresholdsUseAllThreeLines() throws Exception {
        JsonNode puppy = ok(analyze("DOG", 3, 12, "WEEK", "S")), lateLarge = ok(analyze("DOG", 18, 20, "WEEK", "XL")), kitten = ok(analyze("CAT", 1.5, 3, "MONTH", null));
        for (JsonNode analysis : List.of(puppy, lateLarge, kitten)) {
            for (JsonNode row : analysis.get("nutrients")) {
                String status = row.get("status").asText();
                if (status.equals("UNIT_CONVERSION_UNSUPPORTED")) continue;
                boolean allNull = row.get("minimum").isNull() && row.get("caution").isNull() && row.get("upper").isNull();
                assertEquals(allNull, status.equals("NO_STANDARD"), analysis.get("lifeStage") + " " + row.get("nutrientId") + "=" + status);
            }
        }
        for (String key : List.of("COPPER", "IRON", "MANGANESE", "ZINC")) {
            for (JsonNode analysis : List.of(puppy, lateLarge)) {
                JsonNode row = row(analysis, key);
                assertNotEquals("NO_STANDARD", row.get("status").asText(), key);
                assertFalse(row.get("caution").isNull(), key);
            }
        }
        JsonNode selenium = row(kitten, "SELENIUM");
        assertNotEquals("NO_STANDARD", selenium.get("status").asText());
        assertFalse(selenium.get("caution").isNull());
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
