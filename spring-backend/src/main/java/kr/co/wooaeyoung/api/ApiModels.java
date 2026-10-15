package kr.co.wooaeyoung.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public enum Species { DOG, CAT }
    public enum ProductType { FEED, SUPPLEMENT }
    public enum AgeUnit { WEEK, MONTH }

    public record Age(@Positive double value, @NotNull AgeUnit unit) {
        public double days() { return value * (unit == AgeUnit.WEEK ? 7 : 30.4375); }
    }

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
    public record ManualNutrient(@NotBlank String nutrientId, @PositiveOrZero double amount, @NotBlank String unit) {}
    public record ManualItem(
            @NotBlank @Size(max = 100) String name,
            @NotNull ProductType type,
            @Positive double servingAmount,
            @NotBlank String servingUnit,
            @Positive double dailyAmount,
            @NotEmpty List<@Valid ManualNutrient> nutrients) {}

    public record AnalysisRequest(
            @Valid @NotNull Profile profile,
            List<@Valid FeedingItem> items,
            List<@Valid ManualItem> manualItems) {
        public List<FeedingItem> safeItems() { return items == null ? List.of() : items; }
        public List<ManualItem> safeManualItems() { return manualItems == null ? List.of() : manualItems; }
    }

    public record RecommendationRequest(
            @Valid @NotNull Profile profile,
            List<@Valid FeedingItem> items,
            List<@Valid ManualItem> manualItems,
            @Min(1) @Max(3) Integer maxItems) {
        public AnalysisRequest analysisRequest() { return new AnalysisRequest(profile, items, manualItems); }
        public int requestedMaxItems() { return maxItems == null ? 3 : maxItems; }
    }
}
