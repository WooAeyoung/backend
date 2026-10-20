package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 기본 설정(allow-unverified=false)에서 기준 프로필 API 계약. */
@SpringBootTest
@AutoConfigureMockMvc
class StandardProfileContractTest {
    static final List<String> REFERENCE_FIELDS = List.of("sourceDocument", "table", "sourceYear", "verificationStatus", "valueType", "unit", "basis", "note");
    static final List<String> LEGACY_ANALYSIS_FIELDS = List.of("traceId", "standardVersion", "standardSource", "cautionPolicy", "lifeStage",
            "referenceEnergyKcal", "usesEstimatedFeed", "summary", "nutrients", "contributions", "ratios", "warnings");
    static final List<String> LEGACY_NUTRIENT_FIELDS = List.of("nutrientId", "name", "unit", "fromFeed", "fromSupplements", "source", "total", "minimum", "caution", "upper", "status");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    static String request(String species, double weightKg, double age, String unit, String extraProfile, String items, String standardProfile) {
        return "{\"profile\":{\"name\":\"테스트\",\"species\":\"" + species + "\",\"weightKg\":" + weightKg
                + ",\"age\":{\"value\":" + age + ",\"unit\":\"" + unit + "\"}" + extraProfile + "},\"items\":[" + items + "]"
                + (standardProfile == null ? "" : ",\"standardProfile\":\"" + standardProfile + "\"") + "}";
    }
    static final String DOG_FEED = "{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":150,\"unit\":\"G\"}";
    static final String ADULT_DOG = request("DOG", 10, 36, "MONTH", "", DOG_FEED, null);

    JsonNode call(String path, String body) throws Exception {
        String content = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(content);
    }
    JsonNode get(String path) throws Exception {
        return mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    static String withProfile(String body, String profile) { return body.substring(0, body.length() - 1) + ",\"standardProfile\":\"" + profile + "\"}"; }

    // ---- 하위 호환

    @Test void requestWithoutProfileKeepsLegacyFieldsAndOnlyAddsNewOnes() throws Exception {
        JsonNode analysis = call("/api/v1/analyses", ADULT_DOG);
        LEGACY_ANALYSIS_FIELDS.forEach(field -> assertTrue(analysis.has(field), field));
        assertEquals("FEDIAF-2025.09", analysis.get("standardVersion").asText());
        assertEquals(6, analysis.get("nutrients").size());
        for (JsonNode row : analysis.get("nutrients")) LEGACY_NUTRIENT_FIELDS.forEach(field -> assertTrue(row.has(field), field));
        assertTrue(analysis.get("summary").has("deficient") && analysis.get("summary").size() == 5, "summary 키는 그대로");
        for (String added : List.of("standardProfile", "parameterVersion", "parameterVerification", "standardsVerification", "ratioApplicability", "verificationSummary"))
            assertTrue(analysis.has(added), added);
    }

    @Test void healthAndNutrientListWithoutQueryAreUnchanged() throws Exception {
        JsonNode health = get("/health");
        assertEquals(Set.of("status", "standardVersion"), fieldNames(health));
        assertEquals("ok", health.get("status").asText());
        assertEquals("FEDIAF-2025.09", health.get("standardVersion").asText());
        JsonNode nutrients = get("/api/v1/nutrients");
        assertEquals(Set.of("items"), fieldNames(nutrients));
        assertEquals(6, nutrients.get("items").size());
        for (JsonNode item : nutrients.get("items")) assertEquals(Set.of("id", "name", "unit"), fieldNames(item));
    }

    // ---- 독립 실행: 순서를 바꿔도 결과가 같다

    @Test void profilesRunIndependentlyRegardlessOfOrder() throws Exception {
        String fediaf = ADULT_DOG, nias = withProfile(ADULT_DOG, "NIAS_V3");
        JsonNode fediafFirst = withoutTraceId(call("/api/v1/analyses", fediaf)), niasSecond = withoutTraceId(call("/api/v1/analyses", nias));
        JsonNode niasFirst = withoutTraceId(call("/api/v1/analyses", nias)), fediafSecond = withoutTraceId(call("/api/v1/analyses", fediaf));
        assertEquals(fediafFirst, fediafSecond);
        assertEquals(niasFirst, niasSecond);
        assertNotEquals(fediafFirst.get("standardVersion"), niasFirst.get("standardVersion"));
        JsonNode fediafRecommendation = withoutTraceId(call("/api/v1/recommendations", fediaf));
        call("/api/v1/recommendations", nias);
        assertEquals(fediafRecommendation, withoutTraceId(call("/api/v1/recommendations", fediaf)));
    }

    // ---- 버전·출처 노출 (두 프로필)

    @Test void versionsAndPerNutrientProvenanceAreExposedForBothProfiles() throws Exception {
        Map<String, String> versions = Map.of("FEDIAF_2025", "FEDIAF-2025.09", "NIAS_V3", "NIAS-v3");
        for (Map.Entry<String, String> profile : versions.entrySet()) {
            for (String path : List.of("/api/v1/analyses", "/api/v1/recommendations")) {
                JsonNode body = call(path, withProfile(ADULT_DOG, profile.getKey()));
                assertEquals(profile.getKey(), body.get("standardProfile").asText(), path);
                assertEquals(profile.getValue(), body.get("standardVersion").asText(), path);
                assertFalse(body.get("parameterVersion").asText().isBlank(), path);
            }
            JsonNode analysis = call("/api/v1/analyses", withProfile(ADULT_DOG, profile.getKey()));
            assertFalse(analysis.get("standardSource").asText().isBlank());
            for (JsonNode row : analysis.get("nutrients")) {
                assertTrue(row.hasNonNull("verificationStatus"), row.get("nutrientId").asText());
                for (String line : List.of("minimum", "caution", "upper")) {
                    JsonNode reference = row.get("reference").get(line);
                    REFERENCE_FIELDS.forEach(field -> assertTrue(reference.has(field), row.get("nutrientId") + "." + line + "." + field));
                    assertFalse(reference.get("sourceDocument").asText().isBlank());
                    assertFalse(reference.get("table").asText().isBlank());
                }
            }
        }
    }

    // ---- 미검증 차단 (allow-unverified=false)

    @Test void pendingValuesAreNotUsedForJudgement() throws Exception {
        JsonNode analysis = call("/api/v1/analyses", withProfile(ADULT_DOG, "NIAS_V3"));
        assertFalse(analysis.get("standardsVerification").get("allowUnverified").asBoolean());
        assertTrue(analysis.get("referenceEnergyKcal").isNull());
        assertEquals(18, analysis.get("nutrients").size());
        for (JsonNode row : analysis.get("nutrients")) {
            String status = row.get("status").asText();
            if (row.get("verificationStatus").asText().equals("PENDING_VERIFICATION")) {
                assertTrue(Set.of("UNVERIFIED_STANDARD", "UNIT_CONVERSION_UNSUPPORTED").contains(status), row.get("nutrientId") + "=" + status);
                for (String line : List.of("minimum", "caution", "upper")) assertTrue(row.get(line).isNull(), row.get("nutrientId") + "." + line);
            } else {
                assertEquals("NO_STANDARD", status, row.get("nutrientId").asText());
            }
        }
        assertEquals(0, analysis.get("verificationSummary").get("judgedWithUnverified").asInt());
        assertTrue(analysis.get("warnings").toString().contains("기준 미검증"));
        // 주의선만 있는 성분도 '기준 없음'으로 처리되지 않는다.
        for (String key : List.of("COPPER", "IRON", "MANGANESE", "ZINC")) assertEquals("UNVERIFIED_STANDARD", row(analysis, key).get("status").asText(), key);
        JsonNode cat = call("/api/v1/analyses", request("CAT", 4, 36, "MONTH", "", "{\"productId\":\"feed-balanced-cat\",\"dailyAmount\":60,\"unit\":\"G\"}", "NIAS_V3"));
        assertTrue(cat.get("referenceEnergyKcal").isNull());
        assertEquals("UNVERIFIED_STANDARD", row(cat, "SELENIUM").get("status").asText());
        assertEquals("UNCONFIRMED", row(cat, "SELENIUM").get("reference").get("caution").get("table").asText());
        // Ca:P도 미검증 매개변수로 판정하지 않는다.
        assertEquals(0, analysis.get("ratios").size());
        assertEquals("UNVERIFIED_STANDARD", analysis.get("ratioApplicability").get("calciumPhosphorus").asText());
        JsonNode recommendation = call("/api/v1/recommendations", withProfile(ADULT_DOG, "NIAS_V3"));
        assertEquals(0, recommendation.get("items").size());
        assertEquals(1, recommendation.get("warnings").size());
    }

    @Test void iuNutrientTotalsStayNullWithStatusAndWarning() throws Exception {
        JsonNode analysis = call("/api/v1/analyses", withProfile(ADULT_DOG, "NIAS_V3"));
        for (String key : List.of("VITAMIN_D", "VITAMIN_E")) {
            JsonNode row = row(analysis, key);
            assertTrue(row.has("total") && row.get("total").isNull(), key);
            assertEquals("UNIT_CONVERSION_UNSUPPORTED", row.get("status").asText(), key);
        }
        assertTrue(analysis.get("warnings").toString().contains("단위 환산 미구현(BE-V3-010)"));
        assertEquals("FEDIAF-2025.09", call("/api/v1/analyses", ADULT_DOG).get("standardVersion").asText());
    }

    @Test void niasGrowthUsesV3StageAndKeepsPendingStandardsBlocked() throws Exception {
        JsonNode puppy = call("/api/v1/analyses", request("DOG", 3, 12, "WEEK", ",\"adultSize\":\"S\"", DOG_FEED, "NIAS_V3"));
        assertEquals("GROWTH_EARLY", puppy.get("lifeStage").asText());
        assertTrue(puppy.get("referenceEnergyKcal").isNull());
        for (JsonNode row : puppy.get("nutrients")) {
            String status = row.get("status").asText();
            assertTrue(Set.of("UNVERIFIED_STANDARD", "NO_STANDARD", "UNIT_CONVERSION_UNSUPPORTED").contains(status), row.get("nutrientId").asText());
        }
        assertTrue(puppy.get("warnings").toString().contains("매개변수 미검증"));
        assertEquals("UNSUPPORTED", puppy.get("ratioApplicability").get("calciumPhosphorus").asText());
    }

    // ---- 전체 영양소 조회와 빈칸 null

    @Test void nutrientCatalogExposesAll44WithExplicitNulls() throws Exception {
        JsonNode catalog = get("/api/v1/nutrients?standardProfile=NIAS_V3");
        assertEquals("NIAS-v3", catalog.get("standardVersion").asText());
        JsonNode items = catalog.get("items");
        assertEquals(44, items.size());
        assertEquals(18, java.util.stream.StreamSupport.stream(items.spliterator(), false).filter(i -> i.get("implemented").asBoolean()).count());
        JsonNode vitaminK = items.get(43);
        assertEquals("VITAMIN_K", vitaminK.get("id").asText());
        assertEquals(44, vitaminK.get("nutrientNo").asInt());
        for (String group : List.of("minimum", "maximum", "caution"))
            vitaminK.get(group).forEach(cell -> {
                assertTrue(cell.has("value") && cell.get("value").isNull(), "명시적 null");
                assertEquals("NOT_APPLICABLE", cell.get("verificationStatus").asText());
            });
        assertEquals(6, get("/api/v1/nutrients?standardProfile=FEDIAF_2025").get("items").size());
        assertEquals(400, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/nutrients").param("standardProfile", "NOPE"))
                .andReturn().getResponse().getStatus());
    }

    static JsonNode row(JsonNode analysis, String key) {
        for (JsonNode row : analysis.get("nutrients")) if (row.get("nutrientId").asText().equals(key)) return row;
        throw new AssertionError("성분 없음: " + key);
    }
    static Set<String> fieldNames(JsonNode node) { Set<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add); return names; }
    static JsonNode withoutTraceId(JsonNode node) { ((ObjectNode) node).remove("traceId"); return node; }
}
