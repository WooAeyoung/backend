package kr.co.wooaeyoung.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.wooaeyoung.domain.standard.StandardModels.StandardProfileId;

import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public enum Species { DOG, CAT }
    public enum ProductType { FEED, SUPPLEMENT }
    public enum AgeUnit { WEEK, MONTH }

    public record Age(@Positive double value, @NotNull AgeUnit unit) {}

    static final String PROFILE_PATTERN = "FEDIAF_2025|NIAS_V3";
    static final String PROFILE_MESSAGE = "FEDIAF_2025 또는 NIAS_V3여야 합니다.";

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
            // 지정하지 않으면 기존 클라이언트와 같은 FEDIAF_2025. 알 수 없는 값은 422.
            @Pattern(regexp = PROFILE_PATTERN, message = PROFILE_MESSAGE) String standardProfile) {
        public List<FeedingItem> safeItems() { return items == null ? List.of() : items; }
        public List<ManualItem> safeManualItems() { return manualItems == null ? List.of() : manualItems; }
        public StandardProfileId profileId() { return StandardProfileId.parse(standardProfile).orElseThrow(); }
        public AnalysisRequest withStandardProfile(String value) { return new AnalysisRequest(profile, items, manualItems, value); }
    }

    public record RecommendationRequest(
            @Valid @NotNull Profile profile,
            List<@Valid FeedingItem> items,
            List<@Valid ManualItem> manualItems,
            @Min(1) @Max(3) Integer maxItems,
            @Pattern(regexp = PROFILE_PATTERN, message = PROFILE_MESSAGE) String standardProfile) {
        public AnalysisRequest analysisRequest() { return new AnalysisRequest(profile, items, manualItems, standardProfile); }
        public int requestedMaxItems() { return maxItems == null ? 3 : maxItems; }
        public StandardProfileId profileId() { return StandardProfileId.parse(standardProfile).orElseThrow(); }
        public RecommendationRequest withStandardProfile(String value) { return new RecommendationRequest(profile, items, manualItems, maxItems, value); }
    }
}
