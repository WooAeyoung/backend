package ai.wooaeyoung.analyze;

import ai.wooaeyoung.catalog.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * POST /api/session/analyze, GET /api/contributions — 원본 api.py의
 * 저장 없이 즉시 분석하는 엔드포인트(F-012/013/016) 포트.
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final CatalogService catalog;
    private final NutritionService nutrition;

    public AnalyzeController(CatalogService catalog, NutritionService nutrition) {
        this.catalog = catalog;
        this.nutrition = nutrition;
    }

    public record SelectionDto(@NotBlank String productId, double dailyAmountG, Boolean active) {
        FeedingSelection toDomain() {
            return new FeedingSelection(productId, dailyAmountG, active == null || active);
        }
    }

    public record AnalyzeRequest(@NotEmpty List<@Valid SelectionDto> selections) {}

    public record AnalyzeResponse(List<NutrientSummary> summary, List<IntakeRow> intake) {}

    @PostMapping("/session/analyze")
    public AnalyzeResponse analyze(@Valid @RequestBody AnalyzeRequest req) {
        List<FeedingSelection> selections = req.selections().stream().map(SelectionDto::toDomain).toList();
        List<IntakeRow> intake = nutrition.calculateIntake(catalog.products(), selections);
        List<NutrientSummary> summary = nutrition.summarizeIntake(intake, catalog.standards());
        return new AnalyzeResponse(summary, intake);
    }

    @PostMapping("/contributions")
    public List<ProductContribution> contributions(
            @RequestBody AnalyzeRequest req,
            @RequestParam String nutrient
    ) {
        List<FeedingSelection> selections = req.selections().stream().map(SelectionDto::toDomain).toList();
        List<IntakeRow> intake = nutrition.calculateIntake(catalog.products(), selections);
        return nutrition.productContributions(intake, nutrient);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }
}
