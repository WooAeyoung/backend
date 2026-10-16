package kr.co.wooaeyoung.standards;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 성분 하나의 하루 기준선과 그 근거.
 * blockedStatus가 있으면(UNSUPPORTED, UNVERIFIED_STANDARD) limit은 모두 null이고 판정·추천에 쓰지 않는다.
 */
public record NutrientLine(Limit limit, String blockedStatus, VerificationStatus verificationStatus, Map<String, StandardReference> reference) {
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String UNVERIFIED_STANDARD = "UNVERIFIED_STANDARD";
    private static final double KCAL_BASIS = 1000;

    /**
     * @param exception        예외 주의값 레코드(값이 없으면 상한 × cautionRatio)
     * @param derivedCaution   예외 주의값이 없고 상한이 있을 때 주의선의 근거
     * @param kcal             하루 기준 열량. null이면 이 생애주기는 계산하지 않는다(UNSUPPORTED)
     */
    public static NutrientLine build(StandardValue minimum, StandardValue maximum, StandardValue exception, StandardReference derivedCaution,
                                     double cautionRatio, Double kcal, boolean allowUnverified) {
        return build(minimum, maximum, exception, derivedCaution, cautionRatio, kcal, allowUnverified, UNSUPPORTED);
    }

    public static NutrientLine build(StandardValue minimum, StandardValue maximum, StandardValue exception, StandardReference derivedCaution,
                                     double cautionRatio, Double kcal, boolean allowUnverified, String unavailableStatus) {
        boolean exceptionCaution = exception.value() != null;
        StandardReference cautionReference = exceptionCaution || maximum.value() == null ? StandardReference.of(exception) : derivedCaution;
        Map<String, StandardReference> reference = new LinkedHashMap<>();
        reference.put("minimum", StandardReference.of(minimum));
        reference.put("caution", cautionReference);
        reference.put("upper", StandardReference.of(maximum));
        VerificationStatus status = combine(
                minimum.value() == null ? null : minimum.verificationStatus(),
                maximum.value() == null ? null : maximum.verificationStatus(),
                exceptionCaution || maximum.value() != null ? cautionReference.verificationStatus() : null);
        Map<String, StandardReference> frozen = Collections.unmodifiableMap(reference);
        Limit empty = new Limit(null, null, null);
        if (kcal == null) return new NutrientLine(empty,
                status == VerificationStatus.NOT_APPLICABLE && UNVERIFIED_STANDARD.equals(unavailableStatus) ? null : unavailableStatus,
                status, frozen);
        if (status == VerificationStatus.PENDING_VERIFICATION && !allowUnverified) return new NutrientLine(empty, UNVERIFIED_STANDARD, status, frozen);
        Double lower = scale(minimum.value(), kcal), upper = scale(maximum.value(), kcal);
        Double caution = exceptionCaution ? scale(exception.value(), kcal) : upper == null ? null : upper * cautionRatio;
        return new NutrientLine(new Limit(lower, caution, upper), null, status, frozen);
    }

    private static Double scale(Double value, double kcal) { return value == null ? null : kcal / KCAL_BASIS * value; }

    /** 쓰인 근거 중 하나라도 PENDING이면 PENDING, 쓰인 근거가 없으면 NOT_APPLICABLE. */
    private static VerificationStatus combine(VerificationStatus... statuses) {
        VerificationStatus result = VerificationStatus.NOT_APPLICABLE;
        for (VerificationStatus status : statuses) {
            if (status == VerificationStatus.PENDING_VERIFICATION) return status;
            if (status == VerificationStatus.VERIFIED) result = status;
        }
        return result;
    }
}
