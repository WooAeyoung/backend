package kr.co.wooaeyoung.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.wooaeyoung.standards.StandardReference;
import kr.co.wooaeyoung.standards.StandardValue;
import kr.co.wooaeyoung.standards.VerificationStatus;
import kr.co.wooaeyoung.units.Conversion;

import java.util.List;
import java.util.Map;

/**
 * 분석·추천·조회 응답 계약(BE-V3-014). 필드 순서는 JSON 키 순서이며 기존 응답과 같다.
 * 값이 null이어도 키는 항상 있다. {@code @JsonInclude(NON_NULL)} 필드만 해당 프로필에서 키가 없을 수 있다(NIAS_V3 전용 등).
 * 명세: spring-backend/docs/openapi.yaml(OpenApiContractTest가 이 파일과 대조한다).
 */
public final class ApiResponses {
    private ApiResponses() {}

    // ---- 분석 POST /api/v1/analyses

    public record AnalysisResponse(
            String traceId,
            String standardVersion,
            String standardSource,
            String cautionPolicy,
            String lifeStage,
            Double referenceEnergyKcal,
            boolean usesEstimatedFeed,
            Summary summary,
            List<NutrientRow> nutrients,
            List<Contribution> contributions,
            Ratios ratios,
            List<String> warnings,
            String standardProfile,
            String parameterVersion,
            Map<String, VerificationStatus> parameterVerification,
            StandardsVerification standardsVerification,
            RatioApplicability ratioApplicability,
            VerificationSummary verificationSummary,
            /** FEDIAF_2025는 null. */
            EnergyBasis energyBasis) {}

    /** 판정에 쓴 성분만 센다(차단·합계 없음 성분은 verificationSummary에 센다). */
    public record Summary(int deficient, int adequate, int caution, int excess, int noStandard) {}

    public record VerificationSummary(
            int judgedWithVerified,
            int judgedWithUnverified,
            int noStandard,
            int unverifiedStandard,
            int unsupported,
            int unitConversionUnsupported,
            /** NIAS_V3 전용. */
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer insufficientData) {}

    public record NutrientRow(
            String nutrientId,
            String name,
            String unit,
            Double fromFeed,
            Double fromSupplements,
            String source,
            Double total,
            Double minimum,
            Double caution,
            Double upper,
            String status,
            Integer nutrientNo,
            VerificationStatus verificationStatus,
            NutrientReference reference,
            /** NIAS_V3 전용: 제품별 기여 근거. */
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Provenance> provenance,
            /** NIAS_V3 전용: 값이 없어 0으로 더한 제품이 있는지. */
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean unknownContribution) {}

    public record NutrientReference(StandardReference minimum, StandardReference caution, StandardReference upper) {}

    /** 성분 하나에 대한 제품 하나의 기여(NIAS_V3). conversion은 라벨 환산 기록, 없으면 null. */
    public record Provenance(String name, String type, String source, Double amount, Conversion conversion, String note) {}

    public record Contribution(
            String name,
            String type,
            String source,
            Map<String, Double> nutrients,
            /** NIAS_V3 전용: 성분별 ACTUAL·ESTIMATED·UNKNOWN 등. */
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> nutrientSources) {}

    /** 개에서 Ca:P를 판정했을 때만 calciumPhosphorus가 있다(고양이·판정 차단·합계 없음이면 빈 객체). */
    public record Ratios(@JsonInclude(JsonInclude.Include.NON_NULL) CalciumPhosphorusRatio calciumPhosphorus) {}

    public record CalciumPhosphorusRatio(double value, String status) {}

    public record RatioApplicability(String calciumPhosphorus) {}

    public record StandardsVerification(boolean allowUnverified) {}

    /** 기준 열량 근거(NIAS_V3). 운영 설정에서 열량이 차단되면 미검증 매개변수에서 나온 숫자는 null이다. */
    public record EnergyBasis(
            String lifeStage,
            String kSource,
            Double k,
            Double exponent,
            Double referenceEnergyKcal,
            boolean blocked,
            String blockedReason,
            Map<String, VerificationStatus> parameterVerification,
            Double adultK,
            Double catGrowthMultiplier,
            /** 강아지 성장기에만 있다. */
            PuppyGrowth puppyGrowth) {}

    public record PuppyGrowth(
            String sizeClass,
            Double a,
            Double b,
            Double ageWeeks,
            Double percentOfAdultWeight,
            Double expectedAdultWeightRawKg,
            Double expectedAdultWeightKg,
            Boolean clipped,
            SizeRange sizeRange,
            Double puppyIntercept,
            Double puppySlope,
            LargeBreedCalcium largeBreedCalcium) {}

    /** upperKg가 null이면 상한 없는 체급(XXL)이다. */
    public record SizeRange(Double lowerKg, Double upperKg) {}

    public record LargeBreedCalcium(boolean applied, double thresholdKg, double calciumMaximum) {}

    // ---- 추천 POST /api/v1/recommendations

    /** items의 형태는 standardProfile로 정해진다(FEDIAF_2025 → FediafRecommendationItem, NIAS_V3 → NiasRecommendationItem). */
    public record RecommendationResponse<I extends RecommendationItem>(
            String traceId,
            String standardVersion,
            String message,
            List<I> items,
            List<ExcludedProduct> excluded,
            boolean usesEstimatedFeed,
            String standardProfile,
            String parameterVersion,
            Map<String, VerificationStatus> parameterVerification,
            StandardsVerification standardsVerification,
            List<String> warnings) {}

    public sealed interface RecommendationItem permits FediafRecommendationItem, NiasRecommendationItem {}

    public record FediafRecommendationItem(
            String productId,
            String name,
            double dailyAmount,
            String unit,
            double score,
            long fixedNutrients,
            Map<String, String> projectedStatuses) implements RecommendationItem {}

    public record NiasRecommendationItem(
            String productId,
            String name,
            double dailyAmount,
            String unit,
            double score,
            long fixedNutrients,
            double margin,
            long overlap,
            ScoreBreakdown scoreBreakdown,
            Map<String, String> projectedStatuses,
            List<String> fixedNutrientIds,
            /** 안전 여유를 정한 성분. 주의선 있는 성분을 담지 않으면 null. */
            String marginNutrientId,
            List<String> overlapNutrientIds,
            DoseBand doseBand) implements RecommendationItem {}

    public record ScoreBreakdown(long fixedNutrients, double margin, long overlap, double w2, double w3, double w4,
                                 double fixedScore, double marginScore, double overlapPenalty) {}

    public record DoseBand(String source, Integer bandIndex, Double aboveKg, Double maxKg, double dose, boolean beyondLastBand) {}

    public record ExcludedProduct(
            String productId,
            String name,
            String reason,
            /** NIAS_V3 전용: reasons[0].code. */
            @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode,
            /** NIAS_V3 전용. */
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ExclusionReason> reasons) {}

    /** ratioStatus는 code가 CALCIUM_PHOSPHORUS일 때만 있다(LOW, HIGH). */
    public record ExclusionReason(String code, List<String> nutrientIds, @JsonInclude(JsonInclude.Include.NON_NULL) String ratioStatus) {}

    // ---- 조회 GET /api/v1/nutrients, /api/v2/standards/{profile}, /api/v2/parameters/{profile}

    /** GET /api/v1/nutrients(쿼리 없음): 기존 FEDIAF 6개 성분. */
    public record NutrientListResponse(List<NutrientSummary> items) {}

    public record NutrientSummary(String id, String name, String unit) {}

    /** GET /api/v1/nutrients?standardProfile=, GET /api/v2/standards/{profile}. */
    public record NutrientCatalogResponse(
            String standardVersion,
            String standardProfile,
            String parameterVersion,
            Map<String, VerificationStatus> parameterVerification,
            StandardsVerification standardsVerification,
            List<NutrientCatalogItem> items) {}

    /** minimum·maximum·caution은 생애주기 열(또는 종) → 기준값 한 칸. 빈칸은 value=null로 그대로 둔다. */
    public record NutrientCatalogItem(
            String id,
            Integer nutrientNo,
            String name,
            String category,
            String unit,
            String standardUnit,
            String masterBaseUnit,
            boolean implemented,
            String note,
            Map<String, StandardValue> minimum,
            Map<String, StandardValue> maximum,
            Map<String, StandardValue> caution) {}

    /** GET /api/v2/parameters/{profile}. */
    public record ParameterCatalogResponse(
            String standardProfile,
            String standardVersion,
            String parameterVersion,
            Map<String, VerificationStatus> verification,
            boolean allowUnverified) {}
}
