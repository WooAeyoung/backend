package kr.co.wooaeyoung.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.api.ApiResponses.*;
import kr.co.wooaeyoung.standards.DataChecksums;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NutritionService가 만든 Map 응답을 ApiResponses 계약 record로 옮긴다(BE-V3-014).
 * 계산 코드는 바꾸지 않고 응답 경계에서만 타입을 고정한다. 키 이름·순서·값은 그대로이고,
 * 기존 Map.of로 만들어 실행마다 순서가 달랐던 객체(ratios.calciumPhosphorus, FEDIAF excluded, 성분 목록)는 record 선언 순서로 고정된다.
 * Issue #5에서 더한 필드(checksums, labelInput, parameters)는 기존 키 뒤에 붙인다.
 * 실행 중에는 record에 없는 키를 무시한다. 테스트(ResponseContractSnapshotTest)는 엄격 모드로 빠진 키를 잡는다.
 */
@Component
public class ResponseAssembler {
    private final ObjectMapper mapper;
    private final DataChecksums checksums;
    private final StandardLookup lookup;

    public ResponseAssembler(ObjectMapper mapper, DataChecksums checksums, StandardLookup lookup) {
        this.mapper = mapper;
        this.checksums = checksums;
        this.lookup = lookup;
    }

    public AnalysisResponse analysis(Map<String, Object> analysis) {
        return mapper.convertValue(withChecksums(analysis), AnalysisResponse.class);
    }

    public RecommendationResponse<?> recommendation(Map<String, Object> recommendation) {
        Class<? extends RecommendationItem> item = StandardProfile.NIAS_V3.name().equals(recommendation.get("standardProfile"))
                ? NiasRecommendationItem.class : FediafRecommendationItem.class;
        JavaType type = mapper.getTypeFactory().constructParametricType(RecommendationResponse.class, item);
        return mapper.convertValue(withChecksums(recommendation), type);
    }

    public NutrientListResponse nutrients(List<Map<String, String>> items) {
        return new NutrientListResponse(items.stream().map(item -> new NutrientSummary(item.get("id"), item.get("name"), item.get("unit"))).toList());
    }

    /** GET /api/v1/nutrients?standardProfile= (기존 그대로). */
    public NutrientCatalogResponse nutrientCatalog(Map<String, Object> catalog) { return mapper.convertValue(catalog, NutrientCatalogResponse.class); }

    /** GET /api/v2/standards/{profile}: 기존 목록 + labelInput + checksums. */
    public StandardCatalogResponse standardCatalog(Map<String, Object> catalog) {
        Map<String, Object> result = new LinkedHashMap<>(catalog);
        result.put("labelInput", lookup.labelInput(profile(catalog)));
        return mapper.convertValue(withChecksums(result), StandardCatalogResponse.class);
    }

    /** GET /api/v2/parameters/{profile}: 기존 버전·검증 상태 + parameters.json 원문 + checksums. */
    public ParameterCatalogResponse parameterCatalog(Map<String, Object> parameters) {
        Map<String, Object> result = new LinkedHashMap<>(parameters);
        result.put("parameters", lookup.parameters(profile(parameters)));
        return mapper.convertValue(withChecksums(result), ParameterCatalogResponse.class);
    }

    public Checksums checksums(StandardProfile profile) {
        return new Checksums(DataChecksums.ALGORITHM, checksums.standards(profile), checksums.parameters(profile), checksums.nutrientMaster(),
                profile == StandardProfile.NIAS_V3 ? checksums.units() : null, checksums.products());
    }

    private Map<String, Object> withChecksums(Map<String, Object> response) {
        Map<String, Object> result = new LinkedHashMap<>(response);
        result.put("checksums", checksums(profile(response)));
        return result;
    }

    private static StandardProfile profile(Map<String, Object> response) { return StandardProfile.valueOf(String.valueOf(response.get("standardProfile"))); }
}
