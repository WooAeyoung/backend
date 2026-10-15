package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.domain.CatalogService;
import kr.co.wooaeyoung.domain.NutritionService;
import kr.co.wooaeyoung.domain.standard.StandardLoader;
import kr.co.wooaeyoung.domain.standard.StandardRegistry;
import kr.co.wooaeyoung.api.ApiModels.AnalysisRequest;
import kr.co.wooaeyoung.api.ApiModels.RecommendationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 미검증 기준값 차단 확인. NIAS 칼슘 성견 레코드 하나에만 테스트용 임의값(min 0.5, max 50 g/1000 kcal)을 넣는다.
 * 실제 NIAS 기준값이 아니다.
 */
@SpringBootTest
class UnverifiedStandardGateTest {
    @Autowired CatalogService catalog;
    @Autowired ObjectMapper mapper;

    private static final String REQUEST = """
        {"profile":{"name":"테스트","species":"DOG","weightKg":10,"age":{"value":36,"unit":"MONTH"},"completeFeed":true},
         "items":[{"productId":"feed-balanced-dog","dailyAmount":20,"unit":"G"}],"standardProfile":"NIAS_V3","maxItems":3}
        """;

    private NutritionService service(boolean allowUnverified) {
        StandardLoader.Source classpath = path -> new ClassPathResource("standards/" + path).getContentAsString(StandardCharsets.UTF_8);
        StandardLoader.Source withTestValue = path -> {
            String text = classpath.read(path);
            if (!path.equals("nias_v3/records.csv")) return text;
            return text.lines().map(line -> line.startsWith("NIAS_V3,CALCIUM,DOG_ADULT,,,")
                    ? line.replace("NIAS_V3,CALCIUM,DOG_ADULT,,,", "NIAS_V3,CALCIUM,DOG_ADULT,0.5,50,") : line).collect(Collectors.joining("\n"));
        };
        return new NutritionService(catalog, new StandardRegistry(mapper, withTestValue, allowUnverified));
    }

    private JsonNode analyze(NutritionService service) throws Exception {
        return mapper.valueToTree(service.analyze(mapper.readValue(REQUEST, RecommendationRequest.class).analysisRequest()));
    }

    private static JsonNode calcium(JsonNode result) {
        for (JsonNode item : result.get("nutrients")) if (item.get("nutrientId").asText().equals("CALCIUM")) return item;
        throw new AssertionError();
    }

    @Test void unverifiedValuesAreNotUsedInJudgementOrRecommendation() throws Exception {
        NutritionService service = service(false);
        JsonNode calcium = calcium(analyze(service));
        assertThat(calcium.get("status").asText()).isEqualTo("UNVERIFIED_STANDARD");
        assertThat(calcium.get("minimum").isNull()).isTrue();
        assertThat(calcium.at("/reference/appliedInJudgement").asBoolean()).isFalse();
        Map<String, Object> recommendation = service.recommend(mapper.readValue(REQUEST, RecommendationRequest.class));
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) recommendation.get("items");
        assertThat(items).allMatch(item -> ((Number) item.get("fixedNutrients")).longValue() == 0);
        // 미검증 Ca:P 범위는 추천 제외 판단에도 쓰지 않는다.
        assertThat(recommendation.get("excluded").toString()).doesNotContain("칼슘:인 비율");
    }

    @Test void allowFlagAppliesUnverifiedValuesAndIsReported() throws Exception {
        NutritionService service = service(true);
        JsonNode result = analyze(service);
        JsonNode calcium = calcium(result);
        assertThat(calcium.get("status").asText()).isEqualTo("DEFICIENT");
        assertThat(calcium.get("minimum").asDouble()).isCloseTo(result.get("referenceEnergyKcal").asDouble() / 1000 * 0.5, within(1e-12));
        assertThat(calcium.at("/reference/appliedInJudgement").asBoolean()).isTrue();
        assertThat(result.at("/standard/allowUnverifiedStandards").asBoolean()).isTrue();
        assertThat(result.get("warnings").toString()).contains("ALLOW_UNVERIFIED_STANDARDS");
        // 값이 비어 있는 다른 성분은 플래그가 켜져도 기준 없음이다(null이 0이 되지 않는다).
        for (JsonNode item : result.get("nutrients"))
            if (item.get("nutrientId").asText().equals("ZINC")) assertThat(item.get("status").asText()).isEqualTo("NO_STANDARD");
        // Java 데모의 supp-calcium은 칼슘만 500 mg이라, 플래그가 켜지면 Ca:P 1~2 범위를 벗어나 추천에서 제외된다.
        Map<String, Object> recommendation = service.recommend(mapper.readValue(REQUEST, RecommendationRequest.class));
        assertThat(recommendation.get("excluded").toString()).contains("supp-calcium").contains("칼슘:인 비율");
    }

    @Test void requestWithoutProfileStaysFediaf() throws Exception {
        AnalysisRequest request = mapper.readValue(REQUEST.replace(",\"standardProfile\":\"NIAS_V3\"", ""), RecommendationRequest.class).analysisRequest();
        assertThat(service(false).analyze(request).get("standardVersion")).isEqualTo("FEDIAF-2025.09");
    }
}
