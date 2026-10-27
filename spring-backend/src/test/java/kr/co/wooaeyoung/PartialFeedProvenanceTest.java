package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;

import static kr.co.wooaeyoung.StandardProfileContractTest.row;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BE-V3-009 부분 성분표 합산과 성분별 근거(provenance). test 프로필(allow-unverified=true)에서 성견 10 kg 36개월(T1) 기준선을 쓴다.
 * T1 하한: 비타민 D 77.32 IU, 칼슘 0.773 g.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartialFeedProvenanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    static final double VITAMIN_D_MINIMUM = 77.32;

    static String body(String profile, boolean completeFeed, String items, String manualItems) {
        return "{\"profile\":{\"name\":\"출처\",\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":36,\"unit\":\"MONTH\"},\"completeFeed\":" + completeFeed + "},"
                + "\"items\":[" + items + "],\"manualItems\":[" + manualItems + "]" + (profile == null ? "" : ",\"standardProfile\":\"" + profile + "\"") + "}";
    }
    static String manual(String name, String type, double servingAmount, String servingUnit, double dailyAmount, String extra, String... nutrients) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"servingAmount\":" + servingAmount + ",\"servingUnit\":\"" + servingUnit
                + "\",\"dailyAmount\":" + dailyAmount + extra + ",\"nutrients\":[" + String.join(",", nutrients) + "]}";
    }
    static String nutrient(String id, double amount, String unit, String extra) {
        return "{\"nutrientId\":\"" + id + "\",\"amount\":" + amount + ",\"unit\":\"" + unit + "\"" + extra + "}";
    }

    MockHttpServletResponse post(String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/analyses")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse();
    }
    JsonNode analyze(String json) throws Exception {
        MockHttpServletResponse response = post(json);
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus(), content);
        return mapper.readTree(content);
    }
    static List<String> sources(JsonNode row) {
        List<String> result = new ArrayList<>();
        row.get("provenance").forEach(entry -> result.add(entry.get("type").asText() + ":" + entry.get("source").asText()));
        return result;
    }
    static boolean warning(JsonNode analysis, String text) { return analysis.get("warnings").toString().contains(text); }

    // 칼슘 1.2 %(1 g당 0.012 g)만 표기된 수동 사료 150 g
    static final String PARTIAL_FEED = manual("부분 사료", "FEED", 100, "G", 150, "", nutrient("CALCIUM", 1.2, "%", ""));

    @Test @DisplayName("완료 조건: 한 사료에서 칼슘은 실제값, 비타민 D는 추정값으로 함께 반환하고 근거를 남긴다")
    void actualAndEstimatedInOneFeed() throws Exception {
        JsonNode analysis = analyze(body("NIAS_V3", true, "", PARTIAL_FEED));
        JsonNode calcium = row(analysis, "CALCIUM"), vitaminD = row(analysis, "VITAMIN_D");
        assertEquals(1.2 / 100 * 150, calcium.get("total").asDouble(), 1e-12);
        assertEquals("ACTUAL", calcium.get("source").asText());
        assertFalse(calcium.get("unknownContribution").asBoolean());
        JsonNode record = calcium.get("provenance").get(0);
        assertEquals("ACTUAL", record.get("source").asText());
        assertEquals("부분 사료", record.get("name").asText());
        JsonNode conversion = record.get("conversion");
        assertEquals(1.2, conversion.get("originalValue").asDouble());
        assertEquals("%", conversion.get("originalUnit").asText());
        assertEquals("PERCENT", conversion.get("basis").asText());
        assertEquals("g", conversion.get("baseUnit").asText());
        assertEquals("1.2 % ÷ 100 = 0.012 g/g", conversion.get("formula").asText());
        assertTrue(conversion.get("source").asText().contains("처리기준 v3 7.3(3)"));

        assertEquals(VITAMIN_D_MINIMUM, vitaminD.get("total").asDouble(), 0.01);
        assertEquals("ESTIMATED", vitaminD.get("source").asText());
        assertEquals(List.of("FEED:ESTIMATED"), sources(vitaminD));
        assertTrue(vitaminD.get("provenance").get(0).get("conversion").isNull());
        assertTrue(analysis.get("usesEstimatedFeed").asBoolean());
        JsonNode contribution = analysis.get("contributions").get(0);
        assertEquals("ACTUAL", contribution.get("source").asText());
        assertEquals("ACTUAL", contribution.get("nutrientSources").get("CALCIUM").asText());
        assertEquals("ESTIMATED", contribution.get("nutrientSources").get("VITAMIN_D").asText());
        assertTrue(warning(analysis, "사료 상세 성분에 없는 성분은 그 성분만 최소 권장량으로 추정했습니다"));
    }

    @Test @DisplayName("카탈로그 사료도 성분별로 본다: 칼슘 실제, 하한이 있는 구리 추정, 하한이 없는 타우린(성견) 정보 없음")
    void catalogFeedIsCheckedPerNutrient() throws Exception {
        JsonNode analysis = analyze(body("NIAS_V3", true, "{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":150,\"unit\":\"G\"}", ""));
        JsonNode calcium = row(analysis, "CALCIUM");
        assertEquals(0.9 / 100 * 150, calcium.get("total").asDouble(), 1e-12);
        assertEquals("ACTUAL", calcium.get("source").asText());
        assertTrue(calcium.get("provenance").get(0).get("conversion").get("formula").asText().contains("÷ 100 g(1회 제공량)"));
        assertEquals("ESTIMATED", row(analysis, "COPPER").get("source").asText());
        JsonNode taurine = row(analysis, "TAURINE");
        assertTrue(taurine.get("minimum").isNull());
        assertEquals(0, taurine.get("total").asDouble());
        assertTrue(taurine.get("unknownContribution").asBoolean());
        assertEquals("INSUFFICIENT_DATA", row(analysis, "VITAMIN_D").get("status").asText());
        assertTrue(row(analysis, "VITAMIN_D").get("total").isNull());
    }

    @Test @DisplayName("완전사료가 아니면 누락 성분은 0이지만 정보 없음으로 구분된다")
    void missingNutrientWithoutCompleteFeedIsUnknownNotZero() throws Exception {
        JsonNode analysis = analyze(body("NIAS_V3", false, "", PARTIAL_FEED));
        JsonNode vitaminD = row(analysis, "VITAMIN_D"), calcium = row(analysis, "CALCIUM");
        assertEquals(0, vitaminD.get("total").asDouble());
        assertEquals("DEFICIENT", vitaminD.get("status").asText());
        assertTrue(vitaminD.get("unknownContribution").asBoolean());
        assertEquals(List.of("FEED:UNKNOWN"), sources(vitaminD));
        assertFalse(calcium.get("unknownContribution").asBoolean());
        assertFalse(analysis.get("usesEstimatedFeed").asBoolean());
        assertTrue(warning(analysis, "정보 없음(UNKNOWN_CONTRIBUTION)"));
        assertEquals("UNKNOWN", analysis.get("contributions").get(0).get("nutrientSources").get("VITAMIN_D").asText());
    }

    @Test @DisplayName("원료 형태가 없는 비타민 A µg은 INSUFFICIENT_DATA, 나머지 성분은 정상 계산")
    void insufficientDataKeepsOtherNutrients() throws Exception {
        String supplement = manual("멀티", "SUPPLEMENT", 1, "TABLET", 2, "",
                nutrient("VITAMIN_A", 300, "µg", ""), nutrient("ZINC", 10, "mg", ""));
        JsonNode analysis = analyze(body("NIAS_V3", false, "", supplement));
        JsonNode vitaminA = row(analysis, "VITAMIN_A");
        assertEquals("INSUFFICIENT_DATA", vitaminA.get("status").asText());
        assertTrue(vitaminA.get("total").isNull());
        assertTrue(vitaminA.get("fromSupplements").isNull());
        assertEquals("INSUFFICIENT_DATA", vitaminA.get("provenance").get(0).get("source").asText());
        assertTrue(vitaminA.get("provenance").get(0).get("conversion").get("note").asText().contains("원료 형태"));
        JsonNode zinc = row(analysis, "ZINC");
        assertEquals(20, zinc.get("total").asDouble(), 1e-12);   // 10 mg × 2정, 하한 11.13 이상·주의선 35.10 미만
        assertEquals("ADEQUATE_NO_UPPER_LIMIT", zinc.get("status").asText());
        assertEquals(1, analysis.get("verificationSummary").get("insufficientData").asInt());
        assertTrue(warning(analysis, "정보 부족(INSUFFICIENT_DATA): 비타민 A"));
    }

    @Test @DisplayName("원료 미상 간주 값을 고르면 환산하고 note를 남긴다")
    void explicitAssumptionIsConvertedWithNote() throws Exception {
        String supplement = manual("E", "SUPPLEMENT", 1, "CAPSULE", 1, "",
                nutrient("VITAMIN_E", 10, "mg", ",\"form\":\"UNKNOWN_ASSUME_DL_ALPHA_TOCOPHERYL_ACETATE\""));
        JsonNode vitaminE = row(analyze(body("NIAS_V3", false, "", supplement)), "VITAMIN_E");
        assertEquals(10, vitaminE.get("total").asDouble(), 1e-12);
        assertEquals("원료 미상, dl-알파-토코페롤 아세테이트로 간주", vitaminE.get("provenance").get(0).get("conversion").get("note").asText());
    }

    @Test @DisplayName("EPA+DHA: EPA·DHA는 합산, 총 오메가3만 있으면 정보 없음")
    void omega3Handling() throws Exception {
        String split = manual("피쉬", "SUPPLEMENT", 1, "CAPSULE", 1, "", nutrient("EPA", 120, "mg", ""), nutrient("DHA", 80, "mg", ""));
        JsonNode summed = row(analyze(body("NIAS_V3", false, "", split)), "EPA_DHA");
        assertEquals(0.2, summed.get("total").asDouble(), 1e-12);
        assertFalse(summed.get("unknownContribution").asBoolean());
        String total = manual("오메가", "SUPPLEMENT", 1, "CAPSULE", 1, "", nutrient("OMEGA3", 300, "mg", ""));
        JsonNode analysis = analyze(body("NIAS_V3", false, "", total));
        JsonNode epaDha = row(analysis, "EPA_DHA");
        assertEquals(0, epaDha.get("total").asDouble());
        assertTrue(epaDha.get("unknownContribution").asBoolean());
        assertEquals("NO_VALUE", epaDha.get("provenance").get(0).get("conversion").get("status").asText());
        assertTrue(warning(analysis, "총 오메가3 값은 EPA+DHA로 환산하지 않아 NIAS 분석에서 제외했습니다."));
    }

    @Test @DisplayName("'이상' 보증값은 실제 함량이 아니므로 값 없음 → 완전사료면 추정")
    void guaranteedValueIsNotAdded() throws Exception {
        String feed = manual("국내 사료", "FEED", 100, "G", 150, "", nutrient("CALCIUM", 0.04, "%", ",\"guarantee\":\"MIN\""));
        JsonNode calcium = row(analyze(body("NIAS_V3", true, "", feed)), "CALCIUM");
        assertEquals("ESTIMATED", calcium.get("source").asText());
        assertEquals(0.773, calcium.get("total").asDouble(), 0.001);
        assertEquals("NO_VALUE", calcium.get("provenance").get(0).get("conversion").get("status").asText());
    }

    @Test @DisplayName("1000 kcal당 표기에 열량이 없으면 INSUFFICIENT_DATA, 열량을 주면 환산")
    void per1000KcalUsesItemEnergy() throws Exception {
        String without = manual("수입 사료", "FEED", 100, "G", 150, "", nutrient("CALCIUM", 2.5, "g", ",\"basis\":\"PER_1000KCAL\""));
        assertEquals("INSUFFICIENT_DATA", row(analyze(body("NIAS_V3", true, "", without)), "CALCIUM").get("status").asText());
        String with = manual("수입 사료", "FEED", 100, "G", 150, ",\"energyKcalPerKg\":3800", nutrient("CALCIUM", 2.5, "g", ",\"basis\":\"PER_1000KCAL\""));
        assertEquals(2.5 * 3800 / 1000 / 1000 * 150, row(analyze(body("NIAS_V3", true, "", with)), "CALCIUM").get("total").asDouble(), 1e-12);
    }

    @Test @DisplayName("모순된 입력(성분과 맞지 않는 원료)은 기존 AnalysisException 형식(422)")
    void contradictoryManualInputIsRejected() throws Exception {
        String supplement = manual("D", "SUPPLEMENT", 1, "TABLET", 1, "", nutrient("VITAMIN_D", 10, "µg", ",\"form\":\"RETINOL\""));
        MockHttpServletResponse response = post(body("NIAS_V3", false, "", supplement));
        assertEquals(422, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("RETINOL"));
    }

    @Test @DisplayName("FEDIAF_2025는 새 입력 필드를 무시하고 경고만 추가한다(계산 결과는 새 필드가 없을 때와 같음)")
    void fediafIgnoresLabelFieldsWithWarning() throws Exception {
        String plain = manual("E", "SUPPLEMENT", 1, "CAPSULE", 1, "", nutrient("VITAMIN_E", 10, "MG", ""), nutrient("ZINC", 4, "MG", ""));
        String labelled = manual("E", "SUPPLEMENT", 1, "CAPSULE", 1, ",\"moisturePercent\":10,\"energyKcalPerKg\":3800",
                nutrient("VITAMIN_E", 10, "MG", ",\"form\":\"D_ALPHA_TOCOPHEROL\",\"basis\":\"PER_SERVING\""), nutrient("ZINC", 4, "MG", ",\"guarantee\":\"MIN\""));
        String item = "{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":150,\"unit\":\"G\"}";
        for (String profile : new String[]{null, "FEDIAF_2025"}) {
            JsonNode without = analyze(body(profile, true, item, plain)), with = analyze(body(profile, true, item, labelled));
            List<String> expectedWarnings = new ArrayList<>();
            without.get("warnings").forEach(node -> expectedWarnings.add(node.asText()));
            expectedWarnings.add("FEDIAF_2025는 원료 형태·표기 기준·수분·열량 입력을 사용하지 않아 무시했습니다.");
            List<String> actualWarnings = new ArrayList<>();
            with.get("warnings").forEach(node -> actualWarnings.add(node.asText()));
            assertEquals(expectedWarnings, actualWarnings);
            ObjectNode a = without.deepCopy(), b = with.deepCopy();
            for (ObjectNode node : List.of(a, b)) { node.remove("traceId"); node.remove("warnings"); }
            assertEquals(a, b, "FEDIAF 계산 결과가 새 필드 유무와 무관해야 함");
            assertFalse(with.get("nutrients").get(0).has("provenance"));
            assertFalse(with.get("verificationSummary").has("insufficientData"));
        }
        assertFalse(analyze(body(null, true, item, plain)).get("warnings").toString().contains("FEDIAF_2025는 원료 형태"));
    }
}
