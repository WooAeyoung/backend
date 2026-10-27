package kr.co.wooaeyoung.standards;

import kr.co.wooaeyoung.api.ApiModels.AgeUnit;
import kr.co.wooaeyoung.api.ApiModels.Profile;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.domain.AnalysisException;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * NIAS_V3 기준. 기준값·매개변수는 모두 원문 대조 전(PENDING_VERIFICATION)이라 allow-unverified=false에서는 판정에 쓰지 않는다.
 * 성장기 계산은 v3 매개변수표를 사용한다. 기준값이 PENDING이면 운영 판정은 계속 차단한다.
 */
@Component
public class NiasV3Strategy implements NutritionStandardStrategy {
    private static final Set<String> MINIMUM_COLUMNS = Set.of("dog_early", "dog_late", "dog_adult", "cat_growth", "cat_adult");
    private static final Set<String> MAXIMUM_COLUMNS = Set.of("dog_growth", "dog_adult", "cat_growth", "cat_adult");
    /** 대형견 성장기 칼슘 최대의 원문 위치(nutrients.json 표 표기와 같은 형식). */
    private static final String LARGE_BREED_CALCIUM_TABLE = "2-17c 각주 d";

    private final NiasV3Parameters parameters;
    private final ProfileNutrients standards;
    private final NutrientMaster master;
    private final boolean allowUnverified;
    private final List<NutrientMeta> nutrients;

    public NiasV3Strategy(StandardParametersLoader loader, NutrientMaster master, StandardsProperties properties) {
        this.parameters = loader.load(StandardProfile.NIAS_V3, NiasV3Parameters.class);
        if (parameters.profile() != StandardProfile.NIAS_V3)
            throw new IllegalStateException("nias-v3/parameters.json의 profile이 NIAS_V3가 아닙니다: " + parameters.profile());
        this.standards = ProfileNutrients.load(loader, StandardProfile.NIAS_V3);
        standards.check(master, MINIMUM_COLUMNS, MAXIMUM_COLUMNS, Set.of("dog", "cat"), List.of(
                new ProfileNutrients.StageColumns("dog_early", "dog_growth", "dog"),
                new ProfileNutrients.StageColumns("dog_late", "dog_growth", "dog"),
                new ProfileNutrients.StageColumns("dog_adult", "dog_adult", "dog"),
                new ProfileNutrients.StageColumns("cat_growth", "cat_growth", "cat"),
                new ProfileNutrients.StageColumns("cat_adult", "cat_adult", "cat")), true);
        if (standards.nutrients().size() != master.entries().size())
            throw new IllegalStateException("nias-v3/nutrients.json 성분 수가 마스터와 다릅니다: " + standards.nutrients().size());
        this.master = master;
        this.allowUnverified = properties.allowUnverified();
        this.nutrients = standards.analysisNutrients(master);
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

    @Override public List<NutrientMeta> nutrients() { return nutrients; }
    @Override public List<Map<String, Object>> nutrientCatalog() { return standards.catalogView(master); }

    @Override public void validate(Profile profile) {
        NiasV3Parameters.Age age = parameters.age();
        double minimumWeeks = profile.species() == Species.DOG ? age.dogMinimumWeeks() : age.catMinimumWeeks();
        if (age(profile).weeks() < minimumWeeks) throw new AnalysisException(Math.round(minimumWeeks) + "주 미만 개체는 현재 지원하지 않습니다.");
        if (profile.species() != Species.DOG || !isDogGrowth(lifeStage(profile))) return;
        String sizes = String.join(", ", sizeClassNames());
        if (profile.adultSize() == null || profile.adultSize().isBlank())
            throw new AnalysisException("체급 선택 필요: 성장기 강아지는 체급(adultSize: " + sizes + ") 선택이 필요합니다.");
        if (!parameters.growthCurve().sizeClasses().containsKey(profile.adultSize()))
            throw new AnalysisException("지원하지 않는 체급입니다: " + profile.adultSize() + " (" + sizes + ")");
    }

    /** 주 경계(8·14주)는 주로, 개월 경계(4·9·12개월)는 개월로 비교한다. 입력 단위의 값은 환산하지 않고 그대로 쓴다. */
    @Override public String lifeStage(Profile profile) {
        NiasV3Parameters.Age age = parameters.age();
        AgeNorm normalized = age(profile);
        double weeks = normalized.weeks(), months = normalized.months();
        if (profile.species() == Species.CAT) {
            if (months <= age.catStage1EndMonths()) return "CAT_S1";
            if (months <= age.catStage2EndMonths()) return "CAT_S2";
            return months < age.adultMonths() ? "CAT_S3" : "ADULT";
        }
        if (weeks < age.dogEarlyGrowthEndWeeks()) return "GROWTH_EARLY";
        return months < age.adultMonths() ? "GROWTH_LATE" : "ADULT";
    }

    /** E = k × 체중^지수(계산명세서 4장). 운영 설정에서 쓰는 매개변수 섹션이 PENDING이면 null. */
    @Override public Double energy(Profile profile, String stage) {
        if (energyBlocked(stage)) return null;
        return k(profile, stage) * Math.pow(profile.weightKg(), parameters.energy().exponent().of(profile.species()));
    }

    /**
     * 기준 열량의 계산 근거. 운영 설정에서 열량이 차단되면(blocked) 미검증 매개변수에서 나온 숫자는 모두 null로 두고
     * 생애주기·k 근거·차단 이유·검증 상태만 보여 준다.
     */
    @Override public Map<String, Object> energyBasis(Profile profile, String stage, Double kcal) {
        boolean dog = profile.species() == Species.DOG, dogGrowth = dog && isDogGrowth(stage), catGrowth = !dog && !stage.equals("ADULT");
        boolean blocked = energyBlocked(stage);
        Map<String, VerificationStatus> used = energyParameterStatus(stage);
        if (dogGrowth) used.put("largeBreedCalcium", parameters.largeBreedCalcium().verificationStatus());
        NiasV3Parameters.Energy energy = parameters.energy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lifeStage", stage);
        result.put("kSource", dogGrowth ? "PUPPY_GROWTH_CURVE" : catGrowth ? "CAT_GROWTH_MULTIPLIER" : "ADULT_K");
        result.put("k", blocked ? null : k(profile, stage));
        result.put("exponent", blocked ? null : energy.exponent().of(profile.species()));
        result.put("referenceEnergyKcal", kcal);
        result.put("blocked", blocked);
        result.put("blockedReason", blocked ? "매개변수 미검증: " + String.join(", ", energyParameterStatus(stage).entrySet().stream()
                .filter(entry -> entry.getValue() == VerificationStatus.PENDING_VERIFICATION).map(Map.Entry::getKey).toList())
                + " 섹션이 원문 대조 전(PENDING_VERIFICATION)이라 기준 열량을 계산하지 않았습니다." : null);
        result.put("parameterVerification", used);
        result.put("adultK", blocked || dogGrowth ? null : dog ? energy.dogAdultK() : energy.catAdultK());
        result.put("catGrowthMultiplier", blocked || !catGrowth ? null : catMultiplier(stage));
        result.put("puppyGrowth", dogGrowth ? puppyGrowthBasis(profile, blocked ? null : puppyGrowth(profile)) : null);
        return result;
    }

    private Map<String, Object> puppyGrowthBasis(Profile profile, PuppyGrowth growth) {
        boolean shown = growth != null;
        NiasV3Parameters.Energy energy = parameters.energy();
        NiasV3Parameters.LargeBreedCalcium largeBreed = parameters.largeBreedCalcium();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sizeClass", profile.adultSize());
        result.put("a", shown ? growth.curve().a() : null);
        result.put("b", shown ? growth.curve().b() : null);
        result.put("ageWeeks", shown ? weeks(profile) : null);
        result.put("percentOfAdultWeight", shown ? growth.percent() : null);
        result.put("expectedAdultWeightRawKg", shown ? growth.adultRawKg() : null);
        result.put("expectedAdultWeightKg", shown ? growth.adultKg() : null);
        result.put("clipped", shown ? growth.clipped() : null);
        Map<String, Object> range = null;
        if (shown) { range = new LinkedHashMap<>(); range.put("lowerKg", growth.curve().lowerKg()); range.put("upperKg", growth.curve().upperKg()); }
        result.put("sizeRange", range);
        result.put("puppyIntercept", shown ? energy.puppyIntercept() : null);
        result.put("puppySlope", shown ? energy.puppySlope() : null);
        Map<String, Object> calcium = null;
        if (shown) {
            calcium = new LinkedHashMap<>();
            calcium.put("applied", growth.largeBreedCalcium());
            calcium.put("thresholdKg", largeBreed.expectedAdultWeightAtLeastKg());
            calcium.put("calciumMaximum", largeBreed.dogGrowthCalciumMaximum());
        }
        result.put("largeBreedCalcium", calcium);
        return result;
    }

    @Override public Map<String, NutrientLine> lines(Profile profile, String stage, Double kcal) {
        boolean dog = profile.species() == Species.DOG;
        PuppyGrowth growth = dog && isDogGrowth(stage) ? puppyGrowth(profile) : null;
        String minimumColumn = dog ? switch (stage) { case "GROWTH_EARLY" -> "dog_early"; case "GROWTH_LATE" -> "dog_late"; default -> "dog_adult"; }
                : stage.equals("ADULT") ? "cat_adult" : "cat_growth";
        String maximumColumn = dog ? (stage.equals("ADULT") ? "dog_adult" : "dog_growth") : minimumColumn;
        double cautionRatio = parameters.cautionRatio().ratio().of(profile.species());
        Map<String, NutrientLine> result = new LinkedHashMap<>();
        for (NutrientMeta meta : nutrients) {
            ProfileNutrients.Nutrient nutrient = standards.nutrient(meta.id());
            StandardValue maximum = nutrient.maximum().get(maximumColumn);
            if (growth != null && growth.largeBreedCalcium() && "CALCIUM".equals(meta.id())) maximum = largeBreedCalciumMaximum(maximum, growth);
            StandardReference derivedCaution = new StandardReference(parameters.sourceDocument() + " 매개변수표 caution_" + (dog ? "dog" : "cat"),
                    StandardValue.TABLE_NOT_APPLICABLE, null, parameters.cautionRatio().verificationStatus(), ValueType.POLICY, maximum.unit(), standards.basis(),
                    "예외 주의값 없음 → NIAS 최대 × " + cautionRatio);
            result.put(meta.id(), NutrientLine.build(nutrient.minimum().get(minimumColumn), maximum, nutrient.caution().get(dog ? "dog" : "cat"),
                    derivedCaution, cautionRatio, kcal, allowUnverified,
                    NutrientLine.UNVERIFIED_STANDARD));
        }
        return result;
    }

    @Override public String calciumPhosphorusBlockedStatus(String stage) {
        if (!stage.equals("ADULT")) return NutrientLine.UNSUPPORTED;
        if (parameters.calciumPhosphorus().verificationStatus() == VerificationStatus.PENDING_VERIFICATION && !allowUnverified) return NutrientLine.UNVERIFIED_STANDARD;
        return null;
    }

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
            warnings.add(allowUnverified
                    ? "매개변수 미검증: NIAS-v3 매개변수(" + parameters.parameterVersion() + ")는 원문 대조 전이라 기준 열량은 참고값입니다."
                    : "매개변수 미검증: NIAS-v3 매개변수(" + parameters.parameterVersion() + ")는 원문 대조 전이라 기준 열량과 영양 기준선을 계산하지 않았습니다.");
        if (profile.weightKg() > (profile.species() == Species.DOG ? 100 : 15)) warnings.add("WEIGHT_UNUSUAL");
        if (isDogGrowth(stage)) {
            PuppyGrowth growth = puppyGrowth(profile);
            if (growth.clipped()) {
                // 기존 코드값은 유지하고, 계산명세서 3장의 안내 문구를 별도 경고로 더한다.
                warnings.add("WEIGHT_CLIPPED");
                warnings.add("체급 또는 체중을 확인하세요: 예상 성체 체중 " + kg(growth.adultRawKg()) + " kg이 " + growth.sizeClass()
                        + " 범위(" + range(growth.curve()) + ")를 벗어나 " + kg(growth.adultKg()) + " kg으로 절단했습니다.");
            }
        }
        return List.copyOf(warnings);
    }

    @Override public double feedFactor() { return parameters.feedEstimate().feedFactor(); }
    @Override public int defaultMaxItems() { return parameters.recommendation().topN(); }

    @Override public double recommendationScore(long fixed, long overlap) {
        // A안의 1차 점수: 부족 해소를 우선하고, 중복 성분은 감점한다.
        // 기준값이 검증 대기인 운영 모드에서는 NutritionService가 후보를 만들지 않으므로
        // 이 점수식은 allow-unverified 테스트/검증 모드에서만 실제 후보 순위에 사용된다.
        return parameters.recommendation().w2() * fixed - parameters.recommendation().w4() * overlap;
    }

    @Override public String recommendationUnsupportedReason() {
        if (!allowUnverified && parameterVerification().containsValue(VerificationStatus.PENDING_VERIFICATION))
            return "NIAS_V3 추천은 기준 매개변수 원문 대조가 끝난 뒤 제공됩니다.";
        return null;
    }

    /** 나이 정규화(계산명세서 1장). 주 입력이면 개월만, 개월 입력이면 주만 환산한다(주 → 개월 왕복 없음). */
    private record AgeNorm(double weeks, double months) {}

    private AgeNorm age(Profile profile) {
        double value = profile.age().value(), weeksPerMonth = parameters.age().weeksPerMonth();
        return profile.age().unit() == AgeUnit.WEEK ? new AgeNorm(value, value / weeksPerMonth) : new AgeNorm(value * weeksPerMonth, value);
    }

    private double weeks(Profile profile) { return age(profile).weeks(); }

    private static boolean isDogGrowth(String stage) { return stage.equals("GROWTH_EARLY") || stage.equals("GROWTH_LATE"); }

    private List<String> sizeClassNames() {
        return parameters.growthCurve().sizeClasses().entrySet().stream()
                .sorted(Comparator.comparingDouble(entry -> entry.getValue().lowerKg())).map(Map.Entry::getKey).toList();
    }

    /** 계산명세서 4장의 k. 강아지 성장기는 성장곡선(3장)에서 구한다. */
    private double k(Profile profile, String stage) {
        NiasV3Parameters.Energy energy = parameters.energy();
        return switch (stage) {
            case "GROWTH_EARLY", "GROWTH_LATE" -> puppyGrowth(profile).k();
            case "CAT_S1", "CAT_S2", "CAT_S3" -> energy.catAdultK() * catMultiplier(stage);
            default -> profile.species() == Species.DOG ? energy.dogAdultK() : energy.catAdultK();
        };
    }

    private double catMultiplier(String stage) {
        NiasV3Parameters.Energy energy = parameters.energy();
        return switch (stage) {
            case "CAT_S1" -> energy.catStage1Multiplier();
            case "CAT_S2" -> energy.catStage2Multiplier();
            case "CAT_S3" -> energy.catStage3Multiplier();
            default -> throw new IllegalArgumentException("고양이 성장 단계가 아닙니다: " + stage);
        };
    }

    /** 이 생애주기의 열량 계산에 쓰는 매개변수 섹션과 검증 상태. 강아지 성장기는 성장곡선도 쓴다. */
    private Map<String, VerificationStatus> energyParameterStatus(String stage) {
        Map<String, VerificationStatus> result = new LinkedHashMap<>();
        result.put("energy", parameters.energy().verificationStatus());
        if (isDogGrowth(stage)) result.put("growthCurve", parameters.growthCurve().verificationStatus());
        return result;
    }

    private boolean energyBlocked(String stage) {
        return !allowUnverified && energyParameterStatus(stage).containsValue(VerificationStatus.PENDING_VERIFICATION);
    }

    /**
     * 강아지 성장 정보(계산명세서 3장).
     * @param percent      성체 체중 대비 비율(%), 100에서 절단
     * @param adultRawKg   예상 성체 체중 원값
     * @param adultKg      체급 범위로 절단한 예상 성체 체중(하한 미만이면 하한, 상한 초과면 상한, XXL은 하한만)
     * @param clipped      원값이 체급 범위를 벗어났는지(절단은 k에 영향을 주지 않는다)
     */
    private record PuppyGrowth(String sizeClass, NiasV3Parameters.SizeClass curve, double percent,
                               double adultRawKg, double adultKg, boolean clipped, double k, boolean largeBreedCalcium) {}

    private PuppyGrowth puppyGrowth(Profile profile) {
        NiasV3Parameters.SizeClass curve = parameters.growthCurve().sizeClasses().get(profile.adultSize());
        if (curve == null) throw new AnalysisException("지원하지 않는 체급입니다: " + profile.adultSize()); // validate에서 먼저 막는다
        double percent = Math.min(curve.a() * Math.log(weeks(profile)) - curve.b(), 100.0);
        double adultRaw = profile.weightKg() / (percent / 100.0);
        double adult = Math.max(adultRaw, curve.lowerKg());
        if (curve.upperKg() != null) adult = Math.min(adult, curve.upperKg());
        boolean clipped = adultRaw < curve.lowerKg() || (curve.upperKg() != null && adultRaw > curve.upperKg());
        NiasV3Parameters.Energy energy = parameters.energy();
        double k = energy.puppyIntercept() - energy.puppySlope() * (percent / 100.0);
        return new PuppyGrowth(profile.adultSize(), curve, percent, adultRaw, adult, clipped, k,
                adult >= parameters.largeBreedCalcium().expectedAdultWeightAtLeastKg());
    }

    /**
     * 대형견 성장기 칼슘 최대(계산명세서 5장). 값과 검증 상태는 parameters.json largeBreedCalcium에서,
     * 원문 위치는 표 2-17c 각주 d로 보여 준다. 대체 전 값(dog_growth)은 note에 남긴다.
     */
    private StandardValue largeBreedCalciumMaximum(StandardValue original, PuppyGrowth growth) {
        NiasV3Parameters.LargeBreedCalcium largeBreed = parameters.largeBreedCalcium();
        String note = "대형견 성장기 칼슘 최대: 예상 성체 " + kg(growth.adultKg()) + " kg ≥ " + kg(largeBreed.expectedAdultWeightAtLeastKg())
                + " kg → " + kg(original.value()) + " 대신 " + kg(largeBreed.dogGrowthCalciumMaximum()) + " 적용 (parameters.json largeBreedCalcium)";
        return new StandardValue(largeBreed.dogGrowthCalciumMaximum(), original.unit(), original.basis(), original.sourceDocument(),
                LARGE_BREED_CALCIUM_TABLE, original.sourceYear(), largeBreed.verificationStatus(), null, original.valueType(), note);
    }

    private static String range(NiasV3Parameters.SizeClass curve) {
        return curve.upperKg() == null ? kg(curve.lowerKg()) + " kg 초과" : kg(curve.lowerKg()) + "~" + kg(curve.upperKg()) + " kg";
    }

    /** 경고 문구용 표시값: 소수 둘째 자리 반올림, 끝자리 0 제거. */
    private static String kg(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
