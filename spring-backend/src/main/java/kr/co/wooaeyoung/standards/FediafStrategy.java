package kr.co.wooaeyoung.standards;

import kr.co.wooaeyoung.api.ApiModels.AgeUnit;
import kr.co.wooaeyoung.api.ApiModels.Profile;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.domain.AnalysisException;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FediafStrategy implements NutritionStandardStrategy {
    private static final String LARGE_BREED_COLUMN = "dog_late_large_breed";
    private static final Set<String> STAGE_COLUMNS = Set.of("dog_early", "dog_late", "dog_adult", "cat_growth", "cat_adult");

    private final FediafParameters parameters;
    private final ProfileNutrients standards;
    private final NutrientMaster master;
    private final boolean allowUnverified;
    private final List<NutrientMeta> nutrients;

    public FediafStrategy(StandardParametersLoader loader, NutrientMaster master, StandardsProperties properties) {
        this.parameters = loader.load(StandardProfile.FEDIAF_2025, FediafParameters.class);
        if (parameters.profile() != StandardProfile.FEDIAF_2025)
            throw new IllegalStateException("fediaf-2025/parameters.json의 profile이 FEDIAF_2025가 아닙니다: " + parameters.profile());
        this.standards = ProfileNutrients.load(loader, StandardProfile.FEDIAF_2025);
        standards.check(master, STAGE_COLUMNS, STAGE_COLUMNS, Set.of("dog", "cat"), List.of(
                new ProfileNutrients.StageColumns("dog_early", "dog_early", "dog"),
                new ProfileNutrients.StageColumns("dog_late", "dog_late", "dog"),
                new ProfileNutrients.StageColumns("dog_adult", "dog_adult", "dog"),
                new ProfileNutrients.StageColumns("cat_growth", "cat_growth", "cat"),
                new ProfileNutrients.StageColumns("cat_adult", "cat_adult", "cat"),
                new ProfileNutrients.StageColumns(LARGE_BREED_COLUMN, LARGE_BREED_COLUMN, "dog")), false);
        this.master = master;
        this.allowUnverified = properties.allowUnverified();
        this.nutrients = standards.analysisNutrients(master);
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

    @Override public List<NutrientMeta> nutrients() { return nutrients; }
    @Override public List<Map<String, Object>> nutrientCatalog() { return standards.catalogView(master); }

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

    @Override public Map<String, NutrientLine> lines(Profile profile, String stage, Double kcal) {
        String column = column(profile.species(), stage), species = profile.species() == Species.DOG ? "dog" : "cat";
        boolean largeBreed = profile.species() == Species.DOG && stage.equals("GROWTH_LATE") && isLargeBreedBeforeCutoff(profile);
        double cautionRatio = parameters.cautionRatio().ratio().of(profile.species());
        StandardReference derivedCaution = new StandardReference("docs/standards-validation.md 서비스 주의선 정책", StandardValue.TABLE_NOT_APPLICABLE, null,
                parameters.cautionRatio().verificationStatus(), ValueType.POLICY, null, standards.basis(),
                "공식 상한 × " + cautionRatio + " (FEDIAF 공식값 아님)");
        Map<String, NutrientLine> result = new LinkedHashMap<>();
        for (NutrientMeta meta : nutrients) {
            ProfileNutrients.Nutrient nutrient = standards.nutrient(meta.id());
            String stageColumn = largeBreed && nutrient.minimum().containsKey(LARGE_BREED_COLUMN) ? LARGE_BREED_COLUMN : column;
            StandardValue maximum = nutrient.maximum().get(stageColumn);
            StandardReference caution = new StandardReference(derivedCaution.sourceDocument(), derivedCaution.table(), null, derivedCaution.verificationStatus(),
                    derivedCaution.valueType(), maximum.unit(), derivedCaution.basis(), derivedCaution.note());
            result.put(meta.id(), NutrientLine.build(nutrient.minimum().get(stageColumn), maximum, nutrient.caution().get(species), caution, cautionRatio, kcal, allowUnverified));
        }
        return result;
    }

    @Override public String calciumPhosphorusBlockedStatus(String stage) { return null; }

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

    private static String column(Species species, String stage) {
        if (species == Species.CAT) return stage.equals("ADULT") ? "cat_adult" : "cat_growth";
        return switch (stage) { case "GROWTH_EARLY" -> "dog_early"; case "GROWTH_LATE" -> "dog_late"; default -> "dog_adult"; };
    }

    private double days(Profile profile) {
        FediafParameters.Age age = parameters.age();
        return profile.age().value() * (profile.age().unit() == AgeUnit.WEEK ? age.daysPerWeek() : age.daysPerMonth());
    }

    private boolean isLargeBreedBeforeCutoff(Profile profile) {
        FediafParameters.LargeBreedCalcium largeBreed = parameters.largeBreedCalcium();
        return profile.expectedAdultWeightKg() > largeBreed.expectedAdultWeightAboveKg() && days(profile) < largeBreed.ageBelowDays();
    }
}
