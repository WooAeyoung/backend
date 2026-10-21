package kr.co.wooaeyoung.api;

import jakarta.validation.Valid;
import kr.co.wooaeyoung.domain.CatalogService;
import kr.co.wooaeyoung.domain.NutritionService;
import kr.co.wooaeyoung.domain.JarvisService;
import kr.co.wooaeyoung.domain.VetClinicService;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class ApiController {
    private final CatalogService catalog;
    private final NutritionService nutrition;
    private final JarvisService jarvis;
    private final VetClinicService vets;

    public ApiController(CatalogService catalog, NutritionService nutrition, JarvisService jarvis, VetClinicService vets) {
        this.catalog = catalog;
        this.nutrition = nutrition;
        this.jarvis = jarvis;
        this.vets = vets;
    }

    @GetMapping("/api/v1/vets/nearest")
    public Map<String, Object> nearestVets(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "5") int limit) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new IllegalArgumentException("위도 또는 경도 범위를 확인해주세요.");
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return Map.of("origin", Map.of("lat", lat, "lon", lon), "clinics", vets.nearest(lat, lon, safeLimit));
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "standardVersion", StandardProfile.DEFAULT.version());
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

    @GetMapping("/api/v1/nutrients")
    public Map<String, Object> nutrients(@RequestParam(required = false) StandardProfile standardProfile) {
        // 쿼리 미지정이면 기존 응답(FEDIAF 6개, id·name·unit)을 그대로 준다.
        if (standardProfile == null) return Map.of("items", nutrition.nutrients());
        return nutrition.nutrientCatalog(standardProfile);
    }

    @PostMapping("/api/v1/analyses")
    public Map<String, Object> analyses(@Valid @RequestBody ApiModels.AnalysisRequest request) {
        return nutrition.analyze(request);
    }

    @PostMapping("/api/v1/recommendations")
    public Map<String, Object> recommendations(@Valid @RequestBody ApiModels.RecommendationRequest request) {
        return nutrition.recommend(request);
    }

    @PostMapping("/api/v1/jarvis")
    public Map<String, Object> jarvis(@Valid @RequestBody ApiModels.JarvisRequest request) {
        return jarvis.answer(request);
    }

    @GetMapping("/api/v2/standards/{profile}")
    public Map<String, Object> standard(@PathVariable StandardProfile profile) {
        return nutrition.nutrientCatalog(profile);
    }

    @GetMapping("/api/v2/parameters/{profile}")
    public Map<String, Object> parameters(@PathVariable StandardProfile profile) {
        return nutrition.parameterCatalog(profile);
    }
}
