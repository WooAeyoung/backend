package ai.wooaeyoung.analyze;

import ai.wooaeyoung.catalog.CatalogService;
import ai.wooaeyoung.domain.SavedProduct;
import ai.wooaeyoung.feeding.FeedingPlanService;
import ai.wooaeyoung.feeding.FeedingPlanService.FeedingPlanEntry;
import ai.wooaeyoung.pet.Pet;
import ai.wooaeyoung.pet.PetService;
import ai.wooaeyoung.product.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GET /api/pets/{petId}/analyze, /api/pets/{petId}/analyze/contributions — 원본 api.py의
 * 저장된 펫·제품·식단 기반 분석 엔드포인트(F-027) 포트.
 * 즉시 분석용 {@link AnalyzeController}(/api/session/analyze, 데모 카탈로그 기반)와 달리,
 * 이쪽은 SQLite에 저장된 펫/제품/식단(pet·product·feeding 패키지)을 조회해 분석한다.
 * 원본은 pet_id를 쿼리 파라미터로 받았지만, 이 포트는 /api/pets/{petId}/feeding과
 * 일관되게 경로 변수로 받는다.
 */
@RestController
@RequestMapping("/api/pets/{petId}/analyze")
public class PetAnalyzeController {

    private final PetService pets;
    private final ProductService products;
    private final FeedingPlanService plans;
    private final CatalogService catalog;
    private final NutritionService nutrition;

    public PetAnalyzeController(PetService pets, ProductService products, FeedingPlanService plans,
                                 CatalogService catalog, NutritionService nutrition) {
        this.pets = pets;
        this.products = products;
        this.plans = plans;
        this.catalog = catalog;
        this.nutrition = nutrition;
    }

    @GetMapping
    public ResponseEntity<?> analyze(@PathVariable long petId) {
        Pet pet = pets.getPet(petId, null);
        if (pet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "펫을 찾을 수 없습니다"));
        }
        List<SavedProduct> saved = products.listProducts();
        if (saved.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "저장된 제품이 없습니다"));
        }
        List<IntakeRow> intake = nutrition.calculateIntake(
                saved.stream().map(SavedProduct::toCatalogProduct).toList(),
                toSelections(plans.getFeedingPlan(petId)));
        List<NutrientSummary> summary = nutrition.summarizeIntake(intake, catalog.standards());
        return ResponseEntity.ok(new AnalyzeController.AnalyzeResponse(summary, intake));
    }

    @GetMapping("/contributions")
    public ResponseEntity<?> contributions(@PathVariable long petId, @RequestParam String nutrient) {
        Pet pet = pets.getPet(petId, null);
        if (pet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "펫을 찾을 수 없습니다"));
        }
        List<SavedProduct> saved = products.listProducts();
        if (saved.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "저장된 제품이 없습니다"));
        }
        List<IntakeRow> intake = nutrition.calculateIntake(
                saved.stream().map(SavedProduct::toCatalogProduct).toList(),
                toSelections(plans.getFeedingPlan(petId)));
        List<ProductContribution> contributions = nutrition.productContributions(intake, nutrient);
        double totalMg = contributions.stream().mapToDouble(ProductContribution::dailyNutrientMg).sum();
        return ResponseEntity.ok(Map.of("nutrient", nutrient, "contributions", contributions, "totalMg", totalMg));
    }

    private List<FeedingSelection> toSelections(List<FeedingPlanEntry> entries) {
        return entries.stream()
                .map(e -> new FeedingSelection(e.productId(), e.dailyAmountG(), e.active()))
                .toList();
    }
}
