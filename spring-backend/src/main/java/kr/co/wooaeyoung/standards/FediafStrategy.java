package kr.co.wooaeyoung.standards;

import kr.co.wooaeyoung.api.ApiModels.AgeUnit;
import kr.co.wooaeyoung.api.ApiModels.Profile;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.domain.AnalysisException;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FediafStrategy implements NutritionStandardStrategy {
    private static final double KCAL_BASIS = 1000;
    private static final List<NutrientMeta> NUTRIENTS = List.of(
            new NutrientMeta("CALCIUM", "칼슘", "MG"),
            new NutrientMeta("PHOSPHORUS", "인", "MG"),
            new NutrientMeta("VITAMIN_D", "비타민 D", "UG"),
            new NutrientMeta("VITAMIN_E", "비타민 E", "MG"),
            new NutrientMeta("OMEGA3", "오메가3", "MG"),
            new NutrientMeta("ZINC", "아연", "MG"));
    // FEDIAF 2025 Table III-3b·III-4b, 1000 kcal ME당 값(docs/standards-validation.md). 3단계에서 standards/fediaf-2025/nutrients.json으로 옮긴다.
    private static final Map<String, Map<String, Map<String, Standard>>> STANDARDS = standards();
    private static final Standard LARGE_BREED_CALCIUM = new Standard(2500d, 4500d);

    private final FediafParameters parameters;

    public FediafStrategy(StandardParametersLoader loader) {
        this.parameters = loader.load(StandardProfile.FEDIAF_2025, FediafParameters.class);
        if (parameters.profile() != StandardProfile.FEDIAF_2025)
            throw new IllegalStateException("fediaf-2025/parameters.json의 profile이 FEDIAF_2025가 아닙니다: " + parameters.profile());
    }

    @Override public StandardProfile profile() { return StandardProfile.FEDIAF_2025; }
    @Override public String parameterVersion() { return parameters.parameterVersion(); }

    @Override public Map<String, VerificationStatus> parameterVerification() {
        Map<String, VerificationStatus> result = new LinkedHashMap<>();
        result.put("age", parameters.age().verificationStatus());
        result.put("lifeStage", parameters.lifeStage().verificationStatus());
        result.put("energy", parameters.energy().verificationStatus());
        result.put("largeBreedCalcium", parameters.largeBreedCalcium().verificationStatus());
        result.put("cautionRatio", parameters.cautionRatio().verificationStatus());
        result.put("calciumPhosphorus", parameters.calciumPhosphorus().verificationStatus());
        result.put("feedEstimate", parameters.feedEstimate().verificationStatus());
        result.put("recommendation", parameters.recommendation().verificationStatus());
        return Collections.unmodifiableMap(result);
    }

    @Override public List<NutrientMeta> nutrients() { return NUTRIENTS; }

    @Override public void validate(Profile profile) {
        double days = days(profile);
        if (days < parameters.age().minimumDays()) throw new AnalysisException("8주 미만 개체는 현재 지원하지 않습니다.");
        if (profile.species() == Species.DOG && days < parameters.lifeStage().adultDays() &&
                (profile.expectedAdultWeightKg() == null || profile.expectedAdultWeightKg() < profile.weightKg()))
            throw new AnalysisException("12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.");
    }

    @Override public String lifeStage(Profile profile) {
        double days = days(profile);
        FediafParameters.LifeStage stage = parameters.lifeStage();
        if (profile.species() == Species.CAT) return days < stage.adultDays() ? "GROWTH" : "ADULT";
        if (days < stage.dogEarlyGrowthEndDays()) return "GROWTH_EARLY";
        return days < stage.adultDays() ? "GROWTH_LATE" : "ADULT";
    }

    @Override public Double energy(Profile profile, String stage) {
        FediafParameters.Energy energy = parameters.energy(); double factor;
        if (profile.species() == Species.DOG && !stage.equals("ADULT")) factor = energy.puppyIntercept() - energy.puppySlope() * (profile.weightKg() / profile.expectedAdultWeightKg());
        else factor = profile.species() == Species.DOG ? energy.dogAdultK() : stage.equals("ADULT") ? energy.catAdultK() : energy.catGrowthK();
        return factor * Math.pow(profile.weightKg(), energy.exponent().of(profile.species()));
    }

    @Override public Map<String, Limit> thresholds(Profile profile, String stage, Double kcal) {
        Map<String, Standard> standards = new LinkedHashMap<>(STANDARDS.get(profile.species().name()).get(stage));
        if (profile.species() == Species.DOG && stage.equals("GROWTH_LATE") && isLargeBreedBeforeCutoff(profile))
            standards.put("CALCIUM", LARGE_BREED_CALCIUM);
        double cautionRatio = parameters.cautionRatio().ratio().of(profile.species()); Map<String, Limit> result = new LinkedHashMap<>();
        standards.forEach((id, standard) -> { Double min = scale(standard.minimum, kcal), upper = scale(standard.upper, kcal); result.put(id, new Limit(min, upper == null ? null : upper * cautionRatio, upper)); });
        return result;
    }

    @Override public String calciumPhosphorusStatus(Profile profile, double calcium, double phosphorus) {
        if (phosphorus == 0) return "UNAVAILABLE"; double value = calcium / phosphorus; String stage = lifeStage(profile); double upper;
        FediafParameters.CalciumPhosphorus ratio = parameters.calciumPhosphorus();
        if (stage.equals("GROWTH_EARLY")) upper = ratio.dogEarlyGrowthMaximum();
        else if (stage.equals("GROWTH_LATE")) upper = isLargeBreedBeforeCutoff(profile) ? ratio.dogLateGrowthLargeBreedMaximum() : ratio.dogLateGrowthMaximum();
        else upper = ratio.dogAdultMaximum();
        return value < ratio.minimum() ? "LOW" : value > upper ? "HIGH" : "ADEQUATE";
    }

    @Override public String standardSource() { return "FEDIAF Nutritional Guidelines 2025, life-stage values per 1000 kcal ME"; }

    @Override public String cautionPolicy(Species species) {
        return "서비스 조기 경고 기준: 공식 상한의 " + Math.round(parameters.cautionRatio().ratio().of(species) * 100) + "%부터 주의";
    }

    @Override public List<String> analysisWarnings(Profile profile, String stage) {
        return List.of("비타민 E와 오메가3는 현재 제품 단위가 공식 기준과 달라 기준 없음으로 표시하며 추천 점수에서 제외합니다.",
                "상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 공식 상한을 적용하지 않았다는 뜻입니다.");
    }

    @Override public double feedFactor() { return parameters.feedEstimate().feedFactor(); }
    @Override public int defaultMaxItems() { return parameters.recommendation().topN(); }

    @Override public double recommendationScore(long fixed, long overlap) {
        return parameters.recommendation().w2() * fixed - parameters.recommendation().w4() * overlap;
    }

    @Override public String recommendationUnsupportedReason() { return null; }

    private double days(Profile profile) {
        FediafParameters.Age age = parameters.age();
        return profile.age().value() * (profile.age().unit() == AgeUnit.WEEK ? age.daysPerWeek() : age.daysPerMonth());
    }

    private boolean isLargeBreedBeforeCutoff(Profile profile) {
        FediafParameters.LargeBreedCalcium largeBreed = parameters.largeBreedCalcium();
        return profile.expectedAdultWeightKg() > largeBreed.expectedAdultWeightAboveKg() && days(profile) < largeBreed.ageBelowDays();
    }

    private static Double scale(Double value, Double kcal) { return value == null ? null : kcal / KCAL_BASIS * value; }

    private static Map<String, Map<String, Map<String, Standard>>> standards() {
        Map<String, Map<String, Standard>> dog = new LinkedHashMap<>(), cat = new LinkedHashMap<>();
        dog.put("ADULT", lines(1450d,6250d,1160d,4000d,3.975,20d,20.8));
        dog.put("GROWTH_EARLY", lines(2500d,4000d,2250d,null,3.45,20d,25d));
        dog.put("GROWTH_LATE", lines(2000d,4500d,1750d,null,3.125,20d,25d));
        cat.put("ADULT", lines(1330d,null,850d,null,2.0825,187.5,25d));
        cat.put("GROWTH", lines(2500d,null,2100d,null,1.75,187.5,18.8));
        return Map.of("DOG", dog, "CAT", cat);
    }
    private static Map<String, Standard> lines(Double caMin, Double caUpper, Double pMin, Double pUpper, Double dMin, Double dUpper, Double zincMin) {
        Map<String, Standard> result = new LinkedHashMap<>(); result.put("CALCIUM", new Standard(caMin, caUpper)); result.put("PHOSPHORUS", new Standard(pMin, pUpper));
        result.put("VITAMIN_D", new Standard(dMin, dUpper)); result.put("VITAMIN_E", new Standard(null, null)); result.put("OMEGA3", new Standard(null, null)); result.put("ZINC", new Standard(zincMin, null)); return result;
    }
    private record Standard(Double minimum, Double upper) {}
}
