package kr.co.wooaeyoung.standards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * NIAS_V3 계산 매개변수. classpath:standards/nias-v3/parameters.json에서 시작 시 한 번 읽는다.
 * 파일은 scripts/convert_nias_v3_standards.py가 docs/v3/기준값표_매개변수표_v3.xlsx의 매개변수표 시트에서 만든다.
 */
public record NiasV3Parameters(
        @NotNull StandardProfile profile,
        @NotBlank String parameterVersion,
        @NotBlank String description,
        @NotBlank String sourceDocument,
        @Valid @NotNull Age age,
        @Valid @NotNull Energy energy,
        @Valid @NotNull GrowthCurve growthCurve,
        @Valid @NotNull LargeBreedCalcium largeBreedCalcium,
        @Valid @NotNull CautionRatio cautionRatio,
        @Valid @NotNull CalciumPhosphorus calciumPhosphorus,
        @Valid @NotNull FeedEstimate feedEstimate,
        @Valid @NotNull Recommendation recommendation) {

    public record Age(
            @NotNull @Positive Double weeksPerMonth,
            @NotNull @Positive Double dogMinimumWeeks,
            @NotNull @Positive Double catMinimumWeeks,
            @NotNull @Positive Double dogEarlyGrowthEndWeeks,
            @NotNull @Positive Double catStage1EndMonths,
            @NotNull @Positive Double catStage2EndMonths,
            @NotNull @Positive Double adultMonths,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record Energy(
            @Valid @NotNull SpeciesFraction exponent,
            @NotNull @Positive Double dogAdultK,
            @NotNull @Positive Double catAdultK,
            @NotNull @Positive Double puppyIntercept,
            @NotNull @Positive Double puppySlope,
            @NotNull @Positive Double catStage1Multiplier,
            @NotNull @Positive Double catStage2Multiplier,
            @NotNull @Positive Double catStage3Multiplier,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    /** 체급(S~XXL)별 성장곡선. BE-V3-006에서 사용한다. */
    public record GrowthCurve(
            @NotNull @Size(min = 5, max = 5) Map<String, @Valid @NotNull SizeClass> sizeClasses,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {
        public GrowthCurve { sizeClasses = sizeClasses == null ? null : Map.copyOf(sizeClasses); }
    }

    /** upperKg가 null이면 절단 상한이 없다(XXL). */
    public record SizeClass(
            @NotNull @Positive Double a,
            @NotNull @Positive Double b,
            @NotNull @PositiveOrZero Double lowerKg,
            @Positive Double upperKg) {}

    public record LargeBreedCalcium(
            @NotNull @Positive Double expectedAdultWeightAtLeastKg,
            @NotNull @Positive Double dogGrowthCalciumMaximum,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record CautionRatio(
            @Valid @NotNull SpeciesFraction ratio,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record CalciumPhosphorus(
            @NotNull @Positive Double minimum,
            @NotNull @Positive Double maximum,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record FeedEstimate(
            @NotNull @Positive Double feedFactor,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}

    public record Recommendation(
            @NotNull @PositiveOrZero Double w2,
            @NotNull @PositiveOrZero Double w3,
            @NotNull @PositiveOrZero Double w4,
            @NotNull @Min(1) @Max(3) Integer topN,
            @NotNull VerificationStatus verificationStatus, @NotBlank String source) {}
}
