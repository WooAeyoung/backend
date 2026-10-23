package kr.co.wooaeyoung.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.wooaeyoung.standards.StandardProfile;
import kr.co.wooaeyoung.units.LabelBasis;
import kr.co.wooaeyoung.units.LabelValue;
import kr.co.wooaeyoung.units.NutrientForm;

import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public enum Species { DOG, CAT }
    public enum ProductType { FEED, SUPPLEMENT }
    public enum AgeUnit { WEEK, MONTH }

    public record Age(@Positive double value, @NotNull AgeUnit unit) {}

    public record Profile(
            @NotBlank @Size(max = 30) String name,
            @NotNull Species species,
            @DecimalMin("0.0001") @DecimalMax("100") double weightKg,
            @Valid @NotNull Age age,
            String adultSize,
            @DecimalMin("0.0001") @DecimalMax("100") Double expectedAdultWeightKg,
            Boolean completeFeed) {
        public boolean usesCompleteFeed() { return completeFeed == null || completeFeed; }
    }

    public record FeedingItem(@NotBlank String productId, @Positive double dailyAmount, @NotBlank String unit) {}
    /** form·basis·guarantee는 선택값(BE-V3-010). NIAS_V3에서만 쓰고 FEDIAF_2025는 경고와 함께 무시한다. */
    public record ManualNutrient(@NotBlank String nutrientId, @PositiveOrZero double amount, @NotBlank String unit,
                                 NutrientForm form, LabelBasis basis, LabelValue.Guarantee guarantee) {
        public ManualNutrient(String nutrientId, double amount, String unit) { this(nutrientId, amount, unit, null, null, null); }
        public boolean hasLabelDetail() { return form != null || basis != null || guarantee != null; }
    }
    /** moisturePercent(건물 기준 환산)·energyKcalPerKg(1000 kcal당 환산)는 선택값. NIAS_V3에서만 쓴다. */
    public record ManualItem(
            @NotBlank @Size(max = 100) String name,
            @NotNull ProductType type,
            @Positive double servingAmount,
            @NotBlank String servingUnit,
            @Positive double dailyAmount,
            @NotEmpty List<@Valid ManualNutrient> nutrients,
            @DecimalMin("0") @DecimalMax(value = "100", inclusive = false) Double moisturePercent,
            @Positive Double energyKcalPerKg) {
        public ManualItem(String name, ProductType type, double servingAmount, String servingUnit, double dailyAmount, List<ManualNutrient> nutrients) {
            this(name, type, servingAmount, servingUnit, dailyAmount, nutrients, null, null);
        }
        public boolean hasLabelDetail() {
            return moisturePercent != null || energyKcalPerKg != null || nutrients.stream().anyMatch(ManualNutrient::hasLabelDetail);
        }
    }

    public record AnalysisRequest(
            @Valid @NotNull Profile profile,
            List<@Valid FeedingItem> items,
            List<@Valid ManualItem> manualItems,
            StandardProfile standardProfile) {
        public List<FeedingItem> safeItems() { return items == null ? List.of() : items; }
        public List<ManualItem> safeManualItems() { return manualItems == null ? List.of() : manualItems; }
        /** 미지정이면 기존 운영 기준(FEDIAF_2025). */
        public StandardProfile standardProfileOrDefault() { return standardProfile == null ? StandardProfile.DEFAULT : standardProfile; }
    }

    public record RecommendationRequest(
            @Valid @NotNull Profile profile,
            List<@Valid FeedingItem> items,
            List<@Valid ManualItem> manualItems,
            @Min(1) @Max(3) Integer maxItems,
            StandardProfile standardProfile) {
        public AnalysisRequest analysisRequest() { return new AnalysisRequest(profile, items, manualItems, standardProfile); }
        public int requestedMaxItems(int defaultMaxItems) { return maxItems == null ? defaultMaxItems : maxItems; }
    }

    /** 자비스는 분석 결과를 저장하지 않고, 현재 브라우저에서 전달한 결과만 요약한다. */
    public record JarvisRequest(
            @NotBlank @Size(max = 500) String message,
            Map<String, Object> analysis) {}
}
