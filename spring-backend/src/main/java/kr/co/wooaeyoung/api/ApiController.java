package kr.co.wooaeyoung.api;

import jakarta.validation.Valid;
import kr.co.wooaeyoung.domain.CatalogService;
import kr.co.wooaeyoung.domain.NutritionService;
import kr.co.wooaeyoung.domain.standard.StandardModels.StandardProfileId;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class ApiController {
    private final CatalogService catalog;
    private final NutritionService nutrition;

    public ApiController(CatalogService catalog, NutritionService nutrition) {
        this.catalog = catalog;
        this.nutrition = nutrition;
    }

    @GetMapping("/health")
    public Map<String, Object> health(@RequestParam(required = false) String standardProfile) {
        StandardProfileId profile = profile(standardProfile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("standardVersion", nutrition.standardVersion(profile));
        result.put("standard", nutrition.profileSummary(profile));
        result.put("availableProfiles", Arrays.stream(StandardProfileId.values()).map(Enum::name).toList());
        return result;
    }

    @GetMapping("/api/v1/products")
    public Map<String, Object> products(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<Map<String, Object>> items = catalog.search(query, type, safeLimit).stream().map(product -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", product.id()); item.put("barcode", product.barcode()); item.put("name", product.name());
            item.put("brand", product.brand()); item.put("type", product.type()); item.put("servingUnit", product.servingUnit());
            item.put("dataQuality", product.dataQuality());
            return item;
        }).toList();
        return Map.of("items", items);
    }

    /** 기본: 프로필이 분석하는 성분. includeAll=true면 공통 44개 + 프로필 전용 성분 마스터 전체. */
    @GetMapping("/api/v1/nutrients")
    public Map<String, Object> nutrients(@RequestParam(required = false) String standardProfile,
                                         @RequestParam(defaultValue = "false") boolean includeAll) {
        StandardProfileId profile = profile(standardProfile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("standard", nutrition.profileSummary(profile));
        result.put("items", nutrition.nutrients(profile, includeAll));
        return result;
    }

    @PostMapping("/api/v1/analyses")
    public Map<String, Object> analyses(@Valid @RequestBody ApiModels.AnalysisRequest request) {
        return nutrition.analyze(request);
    }

    @PostMapping("/api/v1/recommendations")
    public Map<String, Object> recommendations(@Valid @RequestBody ApiModels.RecommendationRequest request) {
        return nutrition.recommend(request);
    }

    private static StandardProfileId profile(String value) {
        return StandardProfileId.parse(value).orElseThrow(() -> new UnknownStandardProfileException(value));
    }
}
