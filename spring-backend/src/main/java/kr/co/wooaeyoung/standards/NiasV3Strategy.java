package kr.co.wooaeyoung.standards;

import kr.co.wooaeyoung.api.ApiModels.AgeUnit;
import kr.co.wooaeyoung.api.ApiModels.Profile;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.domain.AnalysisException;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * NIAS_V3 기준. 매개변수는 모두 원문 대조 전(PENDING_VERIFICATION)이다.
 * 성장기 계산(성장곡선, 고양이 3단계)은 BE-V3-005~007, 추천식은 BE-V3-012~013 범위라 아직 계산하지 않는다.
 */
@Component
public class NiasV3Strategy implements NutritionStandardStrategy {
    private final NiasV3Parameters parameters;

    public NiasV3Strategy(StandardParametersLoader loader) {
        this.parameters = loader.load(StandardProfile.NIAS_V3, NiasV3Parameters.class);
        if (parameters.profile() != StandardProfile.NIAS_V3)
            throw new IllegalStateException("nias-v3/parameters.json의 profile이 NIAS_V3가 아닙니다: " + parameters.profile());
    }

    @Override public StandardProfile profile() { return StandardProfile.NIAS_V3; }
    @Override public String parameterVersion() { return parameters.parameterVersion(); }

    @Override public Map<String, VerificationStatus> parameterVerification() {
        Map<String, VerificationStatus> result = new LinkedHashMap<>();
        result.put("age", parameters.age().verificationStatus());
        result.put("energy", parameters.energy().verificationStatus());
        result.put("growthCurve", parameters.growthCurve().verificationStatus());
        result.put("largeBreedCalcium", parameters.largeBreedCalcium().verificationStatus());
        result.put("cautionRatio", parameters.cautionRatio().verificationStatus());
        result.put("calciumPhosphorus", parameters.calciumPhosphorus().verificationStatus());
        result.put("feedEstimate", parameters.feedEstimate().verificationStatus());
        result.put("recommendation", parameters.recommendation().verificationStatus());
        return Collections.unmodifiableMap(result);
    }

    // 영양소 기준값은 3단계(BE-V3-003)에서 standards/nias-v3/nutrients.json으로 적재한다.
    @Override public List<NutrientMeta> nutrients() { return List.of(); }

    @Override public void validate(Profile profile) {
        NiasV3Parameters.Age age = parameters.age();
        double minimumWeeks = profile.species() == Species.DOG ? age.dogMinimumWeeks() : age.catMinimumWeeks();
        if (weeks(profile) < minimumWeeks) throw new AnalysisException(Math.round(minimumWeeks) + "주 미만 개체는 현재 지원하지 않습니다.");
    }

    @Override public String lifeStage(Profile profile) {
        NiasV3Parameters.Age age = parameters.age();
        double weeks = weeks(profile), months = weeks / age.weeksPerMonth();
        if (profile.species() == Species.CAT) return months < age.adultMonths() ? "GROWTH" : "ADULT";
        if (weeks < age.dogEarlyGrowthEndWeeks()) return "GROWTH_EARLY";
        return months < age.adultMonths() ? "GROWTH_LATE" : "ADULT";
    }

    @Override public Double energy(Profile profile, String stage) {
        if (!stage.equals("ADULT")) return null;
        NiasV3Parameters.Energy energy = parameters.energy();
        double k = profile.species() == Species.DOG ? energy.dogAdultK() : energy.catAdultK();
        return k * Math.pow(profile.weightKg(), energy.exponent().of(profile.species()));
    }

    @Override public Map<String, Limit> thresholds(Profile profile, String stage, Double kcal) { return Map.of(); }

    @Override public String calciumPhosphorusStatus(Profile profile, double calcium, double phosphorus) {
        if (phosphorus == 0) return "UNAVAILABLE"; double value = calcium / phosphorus;
        NiasV3Parameters.CalciumPhosphorus ratio = parameters.calciumPhosphorus();
        return value < ratio.minimum() ? "LOW" : value > ratio.maximum() ? "HIGH" : "ADEQUATE";
    }

    @Override public String standardSource() {
        return "농촌진흥청 국립축산과학원 「반려동물 사료 영양표준」 표 2-17c·d·g·h(1000 kcal ME당), docs/v3 변환값 — 원문 대조 전(PENDING_VERIFICATION)";
    }

    @Override public String cautionPolicy(Species species) {
        return "서비스 조기 경고 기준: 예외 주의값이 있으면 그 값, 없으면 NIAS 최대의 " + Math.round(parameters.cautionRatio().ratio().of(species) * 100) + "%부터 주의";
    }

    @Override public List<String> analysisWarnings(Profile profile, String stage) {
        List<String> warnings = new ArrayList<>();
        if (parameterVerification().containsValue(VerificationStatus.PENDING_VERIFICATION))
            warnings.add("매개변수 미검증: NIAS-v3 매개변수(" + parameters.parameterVersion() + ")는 원문 대조 전이라 기준 열량은 참고값입니다.");
        if (!stage.equals("ADULT"))
            warnings.add("NIAS_V3 성장기 계산(성장곡선·고양이 3단계)은 BE-V3-005~007 미구현으로 지원하지 않습니다.");
        warnings.add("NIAS_V3 영양소 기준값은 아직 적재되지 않았습니다(BE-V3-003 진행 중).");
        return List.copyOf(warnings);
    }

    @Override public double feedFactor() { return parameters.feedEstimate().feedFactor(); }
    @Override public int defaultMaxItems() { return parameters.recommendation().topN(); }

    @Override public double recommendationScore(long fixed, long overlap) {
        throw new IllegalStateException("NIAS_V3 추천 점수식은 BE-V3-013 범위입니다.");
    }

    @Override public String recommendationUnsupportedReason() {
        return "NIAS_V3 추천은 계산하지 않았습니다: 추천 점수식(BE-V3-012~013) 미구현이며, 판정 가능한 검증 완료 기준값이 없습니다.";
    }

    private double weeks(Profile profile) {
        return profile.age().unit() == AgeUnit.WEEK ? profile.age().value() : profile.age().value() * parameters.age().weeksPerMonth();
    }
}
