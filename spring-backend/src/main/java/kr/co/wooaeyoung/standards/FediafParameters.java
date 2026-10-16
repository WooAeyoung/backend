package kr.co.wooaeyoung.standards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * FEDIAF_2025 계산 매개변수. classpath:standards/fediaf-2025/parameters.json에서 시작 시 한 번 읽는다.
 * 값은 리팩터링 전 Java 계산 코드의 상수를 그대로 옮긴 것이다.
 */
public record FediafParameters(
        @NotNull StandardProfile profile,
        @NotBlank String parameterVersion,
        @NotBlank String description,
        @Valid @NotNull Age age,
        @Valid @NotNull LifeStage lifeStage,
        @Valid @NotNull Energy energy,
        @Valid @NotNull LargeBreedCalcium largeBreedCalcium,
        @Valid @NotNull CautionRatio cautionRatio,
        @Valid @NotNull CalciumPhosphorus calciumPhosphorus,
        @Valid @NotNull FeedEstimate feedEstimate,
        @Valid @NotNull Recommendation recommendation) {

    public record Age(
            @NotNull @Positive Double daysPerWeek,
            @NotNull @Positive Double daysPerMonth,
            @NotNull @Positive Double minimumDays,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record LifeStage(
            @NotNull @Positive Double dogEarlyGrowthEndDays,
            @NotNull @Positive Double adultDays,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record Energy(
            @Valid @NotNull SpeciesFraction exponent,
            @NotNull @Positive Double dogAdultK,
            @NotNull @Positive Double catAdultK,
            @NotNull @Positive Double catGrowthK,
            @NotNull @Positive Double puppyIntercept,
            @NotNull @Positive Double puppySlope,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    /** FEDIAF Table III-3 각주 b: 예상 성체 체중이 이 값을 초과하고 이 나이 미만이면 대형견 칼슘 기준을 쓴다. */
    public record LargeBreedCalcium(
            @NotNull @Positive Double expectedAdultWeightAboveKg,
            @NotNull @Positive Double ageBelowDays,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record CautionRatio(
            @Valid @NotNull SpeciesFraction ratio,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record CalciumPhosphorus(
            @NotNull @Positive Double minimum,
            @NotNull @Positive Double dogEarlyGrowthMaximum,
            @NotNull @Positive Double dogLateGrowthMaximum,
            @NotNull @Positive Double dogLateGrowthLargeBreedMaximum,
            @NotNull @Positive Double dogAdultMaximum,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record FeedEstimate(
            @NotNull @Positive Double feedFactor,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    /** w3(안전 여유)는 현재 Java 추천식에 없으므로 0만 허용한다. */
    public record Recommendation(
            @NotNull @PositiveOrZero Double w2,
            @NotNull @DecimalMin("0") @DecimalMax("0") Double w3,
            @NotNull @PositiveOrZero Double w4,
            @NotNull @Min(1) @Max(3) Integer topN,
            @NotBlank String note,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}
}
