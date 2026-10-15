package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 기준 프로필 분리·요청/응답 계약·미검증 기준값 차단(ALLOW_UNVERIFIED_STANDARDS=false). */
@SpringBootTest
@AutoConfigureMockMvc
class StandardProfilesTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    static String body(String profile, String profileFields, String items) {
        String standard = profile == null ? "" : ",\"standardProfile\":\"" + profile + "\"";
        return "{\"profile\":{\"name\":\"테스트\",\"completeFeed\":true," + profileFields + "},\"items\":" + items + standard + "}";
    }

    static final String ADULT_DOG = "\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":36,\"unit\":\"MONTH\"}";
    static final String DOG_FEED = "[{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":150,\"unit\":\"G\"}]";

    JsonNode postJson(String path, String content) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(response);
    }

    int postStatus(String path, String content) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getStatus();
    }

    JsonNode getJson(String path, String... params) throws Exception {
        var request = get(path);
        for (int index = 0; index < params.length; index += 2) request = request.param(params[index], params[index + 1]);
        return mapper.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static JsonNode stable(JsonNode node) {
        ObjectNode copy = node.deepCopy();
        copy.remove("traceId");
        return copy;
    }

    static JsonNode nutrient(JsonNode result, String id) {
        for (JsonNode item : result.get("nutrients")) if (item.get("nutrientId").asText().equals(id)) return item;
        throw new AssertionError(id);
    }

    @Test void unspecifiedProfileIsFediaf() throws Exception {
        JsonNode implicit = postJson("/api/v1/analyses", body(null, ADULT_DOG, DOG_FEED));
        JsonNode explicit = postJson("/api/v1/analyses", body("FEDIAF_2025", ADULT_DOG, DOG_FEED));
        assertThat(implicit.at("/standard/id").asText()).isEqualTo("FEDIAF_2025");
        assertThat(stable(implicit)).isEqualTo(stable(explicit));
        assertThat(implicit.get("traceId").asText().split("-")[0]).isEqualTo(explicit.get("traceId").asText().split("-")[0]);
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/analyses", "/api/v1/recommendations"})
    void unknownProfileIs422(String path) throws Exception {
        assertThat(postStatus(path, body("NIAS_2024", ADULT_DOG, DOG_FEED))).isEqualTo(422);
    }

    @ParameterizedTest @ValueSource(strings = {"/health", "/api/v1/nutrients"})
    void unknownProfileQueryIs422(String path) throws Exception {
        assertThat(mvc.perform(get(path).param("standardProfile", "UNKNOWN")).andReturn().getResponse().getStatus()).isEqualTo(422);
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/analyses", "/api/v1/recommendations"})
    void sameInputRunsIndependentlyInEitherOrder(String path) throws Exception {
        JsonNode firstFediaf = stable(postJson(path, body("FEDIAF_2025", ADULT_DOG, DOG_FEED)));
        JsonNode firstNias = stable(postJson(path, body("NIAS_V3", ADULT_DOG, DOG_FEED)));
        JsonNode secondNias = stable(postJson(path, body("NIAS_V3", ADULT_DOG, DOG_FEED)));
        JsonNode secondFediaf = stable(postJson(path, body("FEDIAF_2025", ADULT_DOG, DOG_FEED)));
        assertThat(firstFediaf).isEqualTo(secondFediaf);
        assertThat(firstNias).isEqualTo(secondNias);
        assertThat(firstFediaf.at("/standard/id").asText()).isEqualTo("FEDIAF_2025");
        assertThat(firstNias.at("/standard/id").asText()).isEqualTo("NIAS_V3");
    }

    @Test void traceIdsDifferByProfile() throws Exception {
        String fediaf = postJson("/api/v1/analyses", body("FEDIAF_2025", ADULT_DOG, DOG_FEED)).get("traceId").asText();
        String nias = postJson("/api/v1/analyses", body("NIAS_V3", ADULT_DOG, DOG_FEED)).get("traceId").asText();
        assertThat(fediaf.split("-")[0]).isNotEqualTo(nias.split("-")[0]);
    }

    @Test void standardAndReferenceBlocksShowVersionAndSource() throws Exception {
        JsonNode fediaf = postJson("/api/v1/analyses", body("FEDIAF_2025", ADULT_DOG, DOG_FEED));
        assertThat(fediaf.at("/standard/version").asText()).isEqualTo("FEDIAF-2025.09");
        assertThat(fediaf.at("/standard/documentName").asText()).isEqualTo("FEDIAF Nutritional Guidelines 2025");
        assertThat(fediaf.at("/standard/verification/unverified").asInt()).isZero();
        JsonNode calcium = nutrient(fediaf, "CALCIUM").get("reference");
        assertThat(calcium.get("sourceTable").asText()).isEqualTo("Table III-3b");
        assertThat(calcium.get("sourceFootnote").isNull()).isTrue();
        assertThat(calcium.get("unit").asText()).isEqualTo("MG");
        assertThat(calcium.get("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(calcium.get("verifiedAt").asText()).isEqualTo("2026-10-01");
        assertThat(calcium.get("appliedInJudgement").asBoolean()).isTrue();

        JsonNode nias = postJson("/api/v1/analyses", body("NIAS_V3", ADULT_DOG, DOG_FEED));
        assertThat(nias.at("/standard/version").asText()).isEqualTo("NIAS-v3");
        assertThat(nias.at("/standard/publisher").asText()).isEqualTo("농촌진흥청 국립축산과학원");
        JsonNode niasCalcium = nutrient(nias, "CALCIUM");
        assertThat(niasCalcium.at("/reference/sourceTable").asText()).isEqualTo("표 2-17d");
        assertThat(niasCalcium.at("/reference/verificationStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(niasCalcium.get("unit").asText()).isEqualTo("G");
    }

    @Test void largeBreedFediafReferenceNamesFootnote() throws Exception {
        String puppy = "\"species\":\"DOG\",\"weightKg\":12,\"age\":{\"value\":5,\"unit\":\"MONTH\"},\"expectedAdultWeightKg\":30";
        JsonNode result = postJson("/api/v1/analyses", body(null, puppy, DOG_FEED));
        assertThat(nutrient(result, "CALCIUM").at("/reference/sourceFootnote").asText()).contains("footnote b");
    }

    @Test void niasRunsWithUnverifiedProfileAndBlocksJudgement() throws Exception {
        JsonNode result = postJson("/api/v1/analyses", body("NIAS_V3", ADULT_DOG, DOG_FEED));
        assertThat(result.get("nutrients")).hasSize(18);
        Set<String> statuses = new HashSet<>();
        for (JsonNode item : result.get("nutrients")) {
            statuses.add(item.get("status").asText());
            // 빈칸은 응답에서도 0이 아니라 null이다.
            assertThat(item.get("minimum").isNull() && item.get("caution").isNull() && item.get("upper").isNull()).isTrue();
        }
        assertThat(statuses).containsExactly("UNVERIFIED_STANDARD");
        assertThat(result.at("/summary/unverifiedStandard").asInt()).isEqualTo(18);
        assertThat(result.at("/ratios/calciumPhosphorus/status").asText()).isEqualTo("UNVERIFIED_STANDARD");
        assertThat(result.get("warnings").toString()).contains("미검증 프로필");
        assertThat(result.at("/standard/allowUnverifiedStandards").asBoolean()).isFalse();
        assertThat(result.at("/standard/verification/unverifiedParameters").toString()).contains("energyKDogAdult");
    }

    @Test void niasUsesProfileParametersAndConvertsCatalogUnits() throws Exception {
        JsonNode result = postJson("/api/v1/analyses", body("NIAS_V3", ADULT_DOG, DOG_FEED));
        assertThat(result.get("lifeStage").asText()).isEqualTo("DOG_ADULT");
        assertThat(result.get("energyFactor").asDouble()).isEqualTo(110);
        assertThat(result.get("referenceEnergyKcal").asDouble()).isCloseTo(110 * Math.pow(10, .75), within(1e-9));
        // 데모 사료 칼슘 900 mg/100 g × 150 g = 1.35 g, 비타민 D 6 µg × 1.5 × 40 = 360 IU
        assertThat(nutrient(result, "CALCIUM").get("total").asDouble()).isCloseTo(1.35, within(1e-9));
        assertThat(nutrient(result, "VITAMIN_D").get("total").asDouble()).isCloseTo(360, within(1e-9));
        assertThat(nutrient(result, "VITAMIN_E").get("total").asDouble()).isZero();
        assertThat(result.get("warnings").toString()).contains("OMEGA3").contains("VITAMIN_E(MG)");
    }

    @Test void niasLifeStagesAndGrowth() throws Exception {
        String puppy = "\"species\":\"DOG\",\"weightKg\":3,\"age\":{\"value\":12,\"unit\":\"WEEK\"},\"adultSize\":\"S\"";
        JsonNode result = postJson("/api/v1/analyses", body("NIAS_V3", puppy, DOG_FEED));
        assertThat(result.get("lifeStage").asText()).isEqualTo("DOG_GROWTH_EARLY");
        assertThat(result.at("/growth/sizeClass").asText()).isEqualTo("S");
        assertThat(result.get("energyFactor").asDouble()).isCloseTo(254.1 - 1.35 * result.at("/growth/percentOfAdult").asDouble(), within(1e-9));
        String kitten = "\"species\":\"CAT\",\"weightKg\":1.5,\"age\":{\"value\":3,\"unit\":\"MONTH\"}";
        JsonNode cat = postJson("/api/v1/analyses", body("NIAS_V3", kitten, "[{\"productId\":\"feed-balanced-cat\",\"dailyAmount\":40,\"unit\":\"G\"}]"));
        assertThat(cat.get("lifeStage").asText()).isEqualTo("CAT_GROWTH_1");
        assertThat(cat.get("energyFactor").asDouble()).isEqualTo(225);
    }

    @Test void niasPuppyNeedsSizeClassButNotExpectedAdultWeight() throws Exception {
        String puppy = "\"species\":\"DOG\",\"weightKg\":3,\"age\":{\"value\":12,\"unit\":\"WEEK\"}";
        String content = body("NIAS_V3", puppy, DOG_FEED);
        assertThat(postStatus("/api/v1/analyses", content)).isEqualTo(422);
        assertThat(postJson("/api/v1/analyses", content).at("/detail/message").asText()).contains("adultSize");
    }

    @Test void nutrientsEndpointIsProfileSpecific() throws Exception {
        JsonNode fediaf = getJson("/api/v1/nutrients");
        assertThat(fediaf.get("items").findValuesAsText("id")).containsExactly("CALCIUM", "PHOSPHORUS", "VITAMIN_D", "VITAMIN_E", "OMEGA3", "ZINC");
        JsonNode nias = getJson("/api/v1/nutrients", "standardProfile", "NIAS_V3");
        assertThat(nias.get("items")).hasSize(18);
        assertThat(nias.at("/standard/id").asText()).isEqualTo("NIAS_V3");
        JsonNode master = getJson("/api/v1/nutrients", "standardProfile", "NIAS_V3", "includeAll", "true");
        assertThat(master.get("items")).hasSize(44);
        assertThat(master.get("items").findValues("analyzed").stream().filter(JsonNode::asBoolean)).hasSize(18);
    }

    @Test void healthReportsProfile() throws Exception {
        assertThat(getJson("/health").get("standardVersion").asText()).isEqualTo("FEDIAF-2025.09");
        JsonNode nias = getJson("/health", "standardProfile", "NIAS_V3");
        assertThat(nias.get("standardVersion").asText()).isEqualTo("NIAS-v3");
        assertThat(nias.at("/standard/verification/verified").asInt()).isZero();
    }
}
