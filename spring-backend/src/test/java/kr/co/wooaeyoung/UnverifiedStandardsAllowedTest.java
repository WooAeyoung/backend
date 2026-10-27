package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static kr.co.wooaeyoung.StandardProfileContractTest.row;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * test 프로필(allow-unverified=true)에서만 검증 대기 기준값을 판정에 쓴다.
 * 기대값은 docs/v3/계산명세서_v3.md 11장 검산 케이스(T1, T2, T12).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnverifiedStandardsAllowedTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    JsonNode analyze(String species, double weightKg, String manualNutrients) throws Exception {
        String body = "{\"profile\":{\"name\":\"검산\",\"species\":\"" + species + "\",\"weightKg\":" + weightKg
                + ",\"age\":{\"value\":36,\"unit\":\"MONTH\"},\"completeFeed\":false},\"items\":[],"
                + "\"manualItems\":[{\"name\":\"검산용\",\"type\":\"SUPPLEMENT\",\"servingAmount\":1,\"servingUnit\":\"TABLET\",\"dailyAmount\":1,\"nutrients\":["
                + manualNutrients + "]}],\"standardProfile\":\"NIAS_V3\"}";
        String content = mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(content);
    }

    static void assertLine(JsonNode analysis, String key, Double minimum, Double caution, Double upper, double tolerance) {
        JsonNode row = row(analysis, key);
        assertValue(minimum, row.get("minimum"), key + ".minimum", tolerance);
        assertValue(caution, row.get("caution"), key + ".caution", tolerance);
        assertValue(upper, row.get("upper"), key + ".upper", tolerance);
    }
    static void assertValue(Double expected, JsonNode actual, String where, double tolerance) {
        if (expected == null) assertTrue(actual.isNull(), where + " 은(는) null이어야 함: " + actual);
        else assertEquals(expected, actual.asDouble(), tolerance, where);
    }

    @Test void adultDogMatchesT1AndStatesThatUnverifiedValuesWereUsed() throws Exception {
        JsonNode t1 = analyze("DOG", 10, "{\"nutrientId\":\"ZINC\",\"amount\":1,\"unit\":\"MG\"}");
        assertEquals(618.58, t1.get("referenceEnergyKcal").asDouble(), 0.01);
        assertLine(t1, "VITAMIN_D", 77.32, 371.15, 494.86, 0.01);
        assertLine(t1, "VITAMIN_A", 773.2, 38661d, 61858d, 1);
        assertLine(t1, "ZINC", 11.13, 35.10, null, 0.01);
        assertTrue(t1.get("standardsVerification").get("allowUnverified").asBoolean());
        assertTrue(t1.get("warnings").toString().contains("참고 판정"));
        assertTrue(t1.get("verificationSummary").get("judgedWithUnverified").asInt() > 0);
        // 주의선만 있는 성분(구리·철·망간·아연)은 기준 없음이 아니라 하한·주의선으로 판정한다.
        for (String key : new String[] {"COPPER", "IRON", "MANGANESE", "ZINC"}) {
            JsonNode row = row(t1, key);
            assertEquals("DEFICIENT", row.get("status").asText(), key);
            assertFalse(row.get("caution").isNull(), key);
            assertTrue(row.get("upper").isNull(), key);
        }
    }

    @Test void adultCatMatchesT2() throws Exception {
        JsonNode t2 = analyze("CAT", 4, "{\"nutrientId\":\"ZINC\",\"amount\":1,\"unit\":\"MG\"}");
        assertEquals(253.15, t2.get("referenceEnergyKcal").asDouble(), 0.01);
        assertLine(t2, "VITAMIN_A", 210.9, 12658d, 25315d, 1);
        assertLine(t2, "IODINE", 0.0380, 0.2848, 0.5696, 0.0001);
        JsonNode selenium = row(t2, "SELENIUM");
        assertNotEquals("NO_STANDARD", selenium.get("status").asText());
        assertFalse(selenium.get("caution").isNull());
        assertTrue(selenium.get("upper").isNull());
        assertEquals("NOT_APPLICABLE", t2.get("ratioApplicability").get("calciumPhosphorus").asText());
    }

    @Test void dogCalciumPhosphorusMatchesT12() throws Exception {
        JsonNode adequate = analyze("DOG", 10, "{\"nutrientId\":\"CALCIUM\",\"amount\":1.2,\"unit\":\"G\"},{\"nutrientId\":\"PHOSPHORUS\",\"amount\":0.9,\"unit\":\"G\"}");
        assertEquals("ADEQUATE", adequate.get("ratios").get("calciumPhosphorus").get("status").asText());
        assertEquals(1.333, adequate.get("ratios").get("calciumPhosphorus").get("value").asDouble(), 0.001);
        JsonNode high = analyze("DOG", 10, "{\"nutrientId\":\"CALCIUM\",\"amount\":2.5,\"unit\":\"G\"},{\"nutrientId\":\"PHOSPHORUS\",\"amount\":1.0,\"unit\":\"G\"}");
        assertEquals("HIGH", high.get("ratios").get("calciumPhosphorus").get("status").asText());
    }

    @Test void vitaminKHasNoStandardEvenWhenUnverifiedValuesAreAllowed() throws Exception {
        String content = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/nutrients").param("standardProfile", "NIAS_V3"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode vitaminK = mapper.readTree(content).get("items").get(43);
        assertTrue(vitaminK.get("minimum").get("dog_adult").get("value").isNull());
        assertFalse(vitaminK.get("implemented").asBoolean());
    }
}
