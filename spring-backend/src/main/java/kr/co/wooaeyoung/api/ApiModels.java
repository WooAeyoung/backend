package kr.co.wooaeyoung.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.wooaeyoung.standards.StandardProfile;

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
