package kr.co.wooaeyoung.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.api.ApiResponses.*;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * NutritionService가 만든 Map 응답을 ApiResponses 계약 record로 옮긴다(BE-V3-014).
 * 계산 코드는 바꾸지 않고 응답 경계에서만 타입을 고정한다. 키 이름·순서·값은 그대로이고,
 * 기존 Map.of로 만들어 실행마다 순서가 달랐던 객체(ratios.calciumPhosphorus, FEDIAF excluded, 성분 목록)는 record 선언 순서로 고정된다.
 * 실행 중에는 record에 없는 키를 무시한다. 테스트(ResponseContractSnapshotTest)는 엄격 모드로 빠진 키를 잡는다.
 */
@Component
public class ResponseAssembler {
    private final ObjectMapper mapper;

    public ResponseAssembler(ObjectMapper mapper) { this.mapper = mapper; }

    public AnalysisResponse analysis(Map<String, Object> analysis) { return mapper.convertValue(analysis, AnalysisResponse.class); }

    public RecommendationResponse<?> recommendation(Map<String, Object> recommendation) {
        Class<? extends RecommendationItem> item = StandardProfile.NIAS_V3.name().equals(recommendation.get("standardProfile"))
                ? NiasRecommendationItem.class : FediafRecommendationItem.class;
        JavaType type = mapper.getTypeFactory().constructParametricType(RecommendationResponse.class, item);
        return mapper.convertValue(recommendation, type);
    }

    public NutrientListResponse nutrients(List<Map<String, String>> items) {
        return new NutrientListResponse(items.stream().map(item -> new NutrientSummary(item.get("id"), item.get("name"), item.get("unit"))).toList());
    }

    public NutrientCatalogResponse nutrientCatalog(Map<String, Object> catalog) { return mapper.convertValue(catalog, NutrientCatalogResponse.class); }

    public ParameterCatalogResponse parameterCatalog(Map<String, Object> parameters) { return mapper.convertValue(parameters, ParameterCatalogResponse.class); }
}
