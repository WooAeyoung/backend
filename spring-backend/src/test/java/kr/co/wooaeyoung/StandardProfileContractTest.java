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

    // ---- 미검증 참고 판정 (allow-unverified=true)

    @Test void pendingValuesAreUsedOnlyAsExplicitReferenceJudgements() throws Exception {
        JsonNode analysis = call("/api/v1/analyses", withProfile(ADULT_DOG, "NIAS_V3"));
        assertTrue(analysis.get("standardsVerification").get("allowUnverified").asBoolean());
        assertTrue(analysis.get("referenceEnergyKcal").isNumber());
        assertEquals(18, analysis.get("nutrients").size());
        for (JsonNode row : analysis.get("nutrients")) {
            String status = row.get("status").asText();
            if (row.get("verificationStatus").asText().equals("PENDING_VERIFICATION")) {
                assertNotEquals("UNVERIFIED_STANDARD", status, row.get("nutrientId") + "=" + status);
            } else {
                assertEquals("NO_STANDARD", status, row.get("nutrientId").asText());
            }
        }
        assertTrue(analysis.get("verificationSummary").get("judgedWithUnverified").asInt() > 0);
        assertTrue(analysis.get("warnings").toString().contains("참고 판정"));
        for (String key : List.of("COPPER", "IRON", "MANGANESE", "ZINC")) assertNotEquals("NO_STANDARD", row(analysis, key).get("status").asText(), key);
        JsonNode cat = call("/api/v1/analyses", request("CAT", 4, 36, "MONTH", "", "{\"productId\":\"feed-balanced-cat\",\"dailyAmount\":60,\"unit\":\"G\"}", "NIAS_V3"));
        assertTrue(cat.get("referenceEnergyKcal").isNumber());
        assertNotEquals("UNVERIFIED_STANDARD", row(cat, "SELENIUM").get("status").asText());
        assertEquals("UNCONFIRMED", row(cat, "SELENIUM").get("reference").get("caution").get("table").asText());
        assertTrue(analysis.get("ratios").has("calciumPhosphorus"));
        assertEquals("APPLICABLE", analysis.get("ratioApplicability").get("calciumPhosphorus").asText());
        JsonNode recommendation = call("/api/v1/recommendations", withProfile(ADULT_DOG, "NIAS_V3"));
        assertTrue(recommendation.get("items").size() > 0);
        assertTrue(recommendation.get("warnings").toString().contains("검증되지 않은 참고 정보"));
    }

    @Test void iuNutrientTotalsStayNullWithStatusAndWarning() throws Exception {
        JsonNode analysis = call("/api/v1/analyses", withProfile(ADULT_DOG, "NIAS_V3"));
        // BE-V3-010: 데모 제품의 비타민 D(µg)·E(mg)는 원료 형태가 없어 추측하지 않는다(UNIT_CONVERSION_UNSUPPORTED → INSUFFICIENT_DATA).
        for (String key : List.of("VITAMIN_D", "VITAMIN_E")) {
            JsonNode row = row(analysis, key);
            assertTrue(row.has("total") && row.get("total").isNull(), key);
            assertEquals("INSUFFICIENT_DATA", row.get("status").asText(), key);
        }
        assertTrue(analysis.get("warnings").toString().contains("정보 부족(INSUFFICIENT_DATA)"));
        assertEquals(2, analysis.get("verificationSummary").get("insufficientData").asInt());
        assertEquals("FEDIAF-2025.09", call("/api/v1/analyses", ADULT_DOG).get("standardVersion").asText());
    }

    @Test void niasGrowthUsesV3StageAndMarksPendingStandardsAsReference() throws Exception {
        JsonNode puppy = call("/api/v1/analyses", request("DOG", 3, 12, "WEEK", ",\"adultSize\":\"S\"", DOG_FEED, "NIAS_V3"));
        assertEquals("GROWTH_EARLY", puppy.get("lifeStage").asText());
        assertTrue(puppy.get("referenceEnergyKcal").isNumber());
        assertTrue(puppy.get("warnings").toString().contains("기준 열량은 참고값입니다."));
        for (JsonNode row : puppy.get("nutrients")) {
            String status = row.get("status").asText();
            assertNotEquals("UNVERIFIED_STANDARD", status, row.get("nutrientId").asText());
        }
        assertTrue(puppy.get("warnings").toString().contains("매개변수 미검증"));
        assertEquals("UNSUPPORTED", puppy.get("ratioApplicability").get("calciumPhosphorus").asText());
    }

    @Test void niasEnergyBasisShowsReferenceNumbersAndVerificationState() throws Exception {
        JsonNode puppy = call("/api/v1/analyses", request("DOG", 3, 12, "WEEK", ",\"adultSize\":\"S\"", DOG_FEED, "NIAS_V3"));
        JsonNode basis = puppy.get("energyBasis");
        assertEquals("GROWTH_EARLY", basis.get("lifeStage").asText());
        assertEquals("PUPPY_GROWTH_CURVE", basis.get("kSource").asText());
        assertFalse(basis.get("blocked").asBoolean());
        assertTrue(basis.get("blockedReason").isNull());
        for (String field : List.of("k", "exponent", "referenceEnergyKcal")) assertTrue(basis.get(field).isNumber(), field);
        assertTrue(basis.get("adultK").isNull() && basis.get("catGrowthMultiplier").isNull());
        assertEquals("PENDING_VERIFICATION", basis.get("parameterVerification").get("growthCurve").asText());
        JsonNode growth = basis.get("puppyGrowth");
        assertEquals("S", growth.get("sizeClass").asText());
        for (String field : List.of("a", "b", "percentOfAdultWeight", "expectedAdultWeightRawKg", "expectedAdultWeightKg", "clipped", "sizeRange", "largeBreedCalcium"))
            assertFalse(growth.get(field).isNull(), field);
        assertTrue(puppy.get("referenceEnergyKcal").isNumber());

        JsonNode kitten = call("/api/v1/analyses", request("CAT", 1.5, 3, "MONTH", "", "{\"productId\":\"feed-balanced-cat\",\"dailyAmount\":40,\"unit\":\"G\"}", "NIAS_V3")).get("energyBasis");
        assertEquals("CAT_S1", kitten.get("lifeStage").asText());
        assertEquals("CAT_GROWTH_MULTIPLIER", kitten.get("kSource").asText());
        assertFalse(kitten.get("blocked").asBoolean());
        assertTrue(kitten.get("blockedReason").isNull());
        assertTrue(kitten.get("k").isNumber() && kitten.get("catGrowthMultiplier").isNumber() && kitten.get("puppyGrowth").isNull());
    }

    @Test void fediafAnalysisAddsNullEnergyBasisOnly() throws Exception {
        JsonNode analysis = call("/api/v1/analyses", ADULT_DOG);
        assertTrue(analysis.has("energyBasis") && analysis.get("energyBasis").isNull());
        assertTrue(analysis.get("referenceEnergyKcal").isNumber());
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
