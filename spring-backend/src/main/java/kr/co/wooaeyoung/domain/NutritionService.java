package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.domain.standard.StandardModels.*;
import kr.co.wooaeyoung.domain.standard.StandardRegistry;
import kr.co.wooaeyoung.domain.standard.UnitConversion;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 요청의 standardProfile(기본 FEDIAF_2025)에 맞는 기준 프로필로 분석·추천한다. 기준값과 매개변수는 StandardRegistry에서 읽는다. */
@Service
public class NutritionService {
    public static final String UNVERIFIED_STANDARD = "UNVERIFIED_STANDARD";
    private static final Map<String, Double> UNIT = Map.of("UG", .001, "MG", 1d, "G", 1000d);

    private final CatalogService catalog;
    private final StandardRegistry registry;

    public NutritionService(CatalogService catalog, StandardRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    public Map<String, Object> analyze(AnalysisRequest raw) {
        AnalysisRequest request = raw.withStandardProfile(raw.profileId().name());
        StandardProfile profile = registry.profile(request.profileId());
        Calc c = new Calc(profile);
        validate(request, c);
        String stage = lifeStage(request, c);
        Energy energy = energy(request, stage, c);
        Lines lines = lines(request, stage, energy, c);
        Aggregate aggregate = aggregate(request, lines.limits(), profile);
        boolean legacy = profile.legacy();
        List<Map<String, Object>> nutrients = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("deficient", 0); summary.put("adequate", 0); summary.put("caution", 0);
        summary.put("excess", 0); summary.put("noStandard", 0);
        if (!legacy) summary.put("unverifiedStandard", 0);
        Map<String, Double> current = new LinkedHashMap<>();
        for (ProfileNutrient item : profile.nutrients()) {
            String id = item.id();
            Total total = aggregate.totals().get(id);
            double value = total.feed + total.supplement;
            current.put(id, value);
            Limit line = lines.limits().get(id);
            String status = classify(value, line);
            String bucket = switch (status) {
                case "DEFICIENT" -> "deficient"; case "CAUTION" -> "caution";
                case "EXCESS" -> "excess"; case "NO_STANDARD" -> "noStandard";
                case UNVERIFIED_STANDARD -> "unverifiedStandard"; default -> "adequate";
            };
            summary.put(bucket, summary.get(bucket) + 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nutrientId", id); row.put("name", registry.master(profile.id(), id).nameKo()); row.put("unit", item.unit());
            row.put("fromFeed", total.feed); row.put("fromSupplements", total.supplement); row.put("source", total.source);
            row.put("total", value); row.put("minimum", line.minimum()); row.put("caution", line.caution()); row.put("upper", line.upper()); row.put("status", status);
            row.put("reference", lines.references().get(id));
            nutrients.add(row);
        }
        Map<String, Object> ratios = new LinkedHashMap<>();
        if (request.profile().species() == Species.DOG) {
            double phosphorus = current.get("PHOSPHORUS");
            Double ratio = phosphorus == 0 ? null : current.get("CALCIUM") / phosphorus;
            String status = calciumPhosphorusStatus(request, current.get("CALCIUM"), phosphorus, c);
            if (legacy) ratios.put("calciumPhosphorus", Map.of("value", ratio == null ? 0d : ratio, "status", status));
            else {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("value", ratio); value.put("status", status);
                ratios.put("calciumPhosphorus", value);
            }
        }
        Map<String, Object> standard = standardBlock(profile, lines.references(), c);
        long cautionPercent = Math.round(profile.param("cautionRatio" + title(request.profile().species())) * 100);
        List<String> warnings = new ArrayList<>(aggregate.warnings());
        String cautionPolicy;
        if (legacy) {
            warnings.add("비타민 E와 오메가3는 현재 제품 단위가 공식 기준과 달라 기준 없음으로 표시하며 추천 점수에서 제외합니다.");
            warnings.add("상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 공식 상한을 적용하지 않았다는 뜻입니다.");
            cautionPolicy = "서비스 조기 경고 기준: 공식 상한의 " + cautionPercent + "%부터 주의";
        } else {
            @SuppressWarnings("unchecked") List<String> standardWarnings = (List<String>) standard.get("warnings");
            warnings.addAll(standardWarnings);
            warnings.add("상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 상한을 적용하지 않았다는 뜻입니다.");
            warnings.add("임신·수유 중인 개체는 지원하지 않습니다.");
            if (energy.growth() != null && Boolean.TRUE.equals(energy.growth().get("outOfRange")))
                warnings.add(0, "예상 성체 체중이 선택한 체급 범위를 벗어났습니다. 체급 또는 체중을 확인하세요.");
            cautionPolicy = "서비스 조기 경고 기준: 상한의 " + cautionPercent + "%부터 주의";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", fingerprint(request) + "-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("standardVersion", profile.version());
        result.put("standardSource", profile.sourceText());
        result.put("cautionPolicy", cautionPolicy);
        result.put("lifeStage", stage); result.put("referenceEnergyKcal", energy.kcal()); result.put("usesEstimatedFeed", aggregate.estimated());
        result.put("summary", summary); result.put("nutrients", nutrients); result.put("contributions", aggregate.contributions());
        result.put("ratios", ratios); result.put("warnings", warnings);
        if (!legacy) { result.put("energyFactor", energy.factor()); result.put("growth", energy.growth()); }
        result.put("standard", standard);
        return result;
    }

    public Map<String, Object> recommend(RecommendationRequest raw) {
        RecommendationRequest request = raw.withStandardProfile(raw.profileId().name());
        AnalysisRequest analysisRequest = request.analysisRequest();
        StandardProfile profile = registry.profile(request.profileId());
        Map<String, Object> base = analyze(analysisRequest);
        @SuppressWarnings("unchecked") List<Map<String, Object>> nutrientRows = (List<Map<String, Object>>) base.get("nutrients");
        Map<String, Double> current = new LinkedHashMap<>();
        Map<String, Limit> limits = new LinkedHashMap<>();
        Map<String, String> original = new LinkedHashMap<>();
        for (Map<String, Object> row : nutrientRows) {
            String id = row.get("nutrientId").toString();
            current.put(id, ((Number) row.get("total")).doubleValue());
            limits.put(id, new Limit(asDouble(row.get("minimum")), asDouble(row.get("caution")), asDouble(row.get("upper")),
                    UNVERIFIED_STANDARD.equals(row.get("status"))));
            original.put(id, row.get("status").toString());
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Map<String, Object>> excluded = new LinkedHashMap<>();
        List<Offer> remaining = new ArrayList<>();
        for (CatalogService.Product product : catalog.all()) {
            if (!product.type().equals("SUPPLEMENT") || product.recommendedDailyAmount() == null) continue;
            Map<String, Double> amounts = productAmounts(product, profile, null);
            if (!amounts.isEmpty()) remaining.add(new Offer(product, amounts));
        }
        for (int round = 0; round < request.requestedMaxItems(); round++) {
            Map<String, String> roundOriginal = original;
            Candidate best = null;
            for (Offer offer : remaining) {
                CatalogService.Product product = offer.product();
                double servingRatio = product.recommendedDailyAmount() / product.servingAmount();
                Map<String, Double> projected = new LinkedHashMap<>(current);
                offer.amounts().forEach((id, amount) -> projected.computeIfPresent(id, (key, value) -> value + amount * servingRatio));
                Map<String, String> statuses = new LinkedHashMap<>();
                projected.forEach((id, value) -> statuses.put(id, classify(value, limits.get(id))));
                List<String> harmful = offer.amounts().keySet().stream()
                        .filter(id -> Set.of("CAUTION", "EXCESS").contains(statuses.get(id))).map(id -> registry.master(profile.id(), id).nameKo()).toList();
                List<String> reasons = new ArrayList<>(harmful);
                if (analysisRequest.profile().species() == Species.DOG &&
                        (!Collections.disjoint(offer.amounts().keySet(), Set.of("CALCIUM", "PHOSPHORUS")))) {
                    String ratioStatus = calciumPhosphorusStatus(analysisRequest, projected.get("CALCIUM"), projected.get("PHOSPHORUS"), new Calc(profile));
                    if (Set.of("LOW", "HIGH").contains(ratioStatus)) reasons.add("칼슘:인 비율");
                }
                if (!reasons.isEmpty()) {
                    excluded.put(product.id(), Map.of("productId", product.id(), "name", product.name(), "reason", "추가 후 기준 이탈 예상: " + String.join(", ", reasons)));
                    continue;
                }
                long fixed = roundOriginal.entrySet().stream().filter(entry -> entry.getValue().equals("DEFICIENT") && !statuses.get(entry.getKey()).equals("DEFICIENT")).count();
                // 미검증 기준 성분은 추천 점수에서 뺀다.
                long overlap = offer.amounts().keySet().stream().filter(id -> !Set.of("DEFICIENT", "NO_STANDARD", UNVERIFIED_STANDARD).contains(roundOriginal.get(id))).count();
                double score = 10 * fixed - overlap;
                Candidate candidate = new Candidate(score, fixed, product, projected, statuses);
                if (best == null || candidate.compareTo(best) > 0) best = candidate;
            }
            if (best == null) break;
            CatalogService.Product product = best.product;
            selected.add(Map.of("productId", product.id(), "name", product.name(), "dailyAmount", product.recommendedDailyAmount(),
                    "unit", product.servingUnit(), "score", best.score, "fixedNutrients", best.fixed, "projectedStatuses", best.statuses));
            current = best.projected; original = best.statuses;
            remaining.removeIf(item -> item.product().id().equals(product.id()));
        }
        @SuppressWarnings("unchecked") Map<String, Integer> summary = (Map<String, Integer>) base.get("summary");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", base.get("traceId")); result.put("standardVersion", profile.version());
        result.put("message", summary.get("deficient") == 0 ? "적용 가능한 기준에서 주의·과다가 검출되지 않은 후보입니다." : "부족 성분, 기준선까지의 여유, 중복 성분을 함께 고려했습니다.");
        result.put("items", selected); result.put("excluded", new ArrayList<>(excluded.values())); result.put("usesEstimatedFeed", base.get("usesEstimatedFeed"));
        result.put("standard", base.get("standard"));
        return result;
    }

    /** 프로필 요약(검증 상태 개수 포함). /health와 /api/v1/nutrients에서 쓴다. */
    public Map<String, Object> profileSummary(StandardProfileId id) {
        StandardProfile profile = registry.profile(id);
        Map<String, Object> summary = identity(profile);
        summary.put("verification", counts(profile.records().stream().map(record -> record.verificationStatus().name()).toList()));
        return summary;
    }

    /** 기본: 프로필이 분석하는 성분. includeAll이면 공통 44개 + 프로필 전용 성분 마스터 전체. */
    public List<Map<String, Object>> nutrients(StandardProfileId id, boolean includeAll) {
        StandardProfile profile = registry.profile(id);
        Map<String, NutrientMaster> masters = registry.masterNutrients(profile.id());
        Map<String, String> units = new LinkedHashMap<>();
        profile.nutrients().forEach(item -> units.put(item.id(), item.unit()));
        List<String> ids = includeAll
                ? masters.values().stream().sorted(Comparator.comparingInt(NutrientMaster::sortOrder)).map(NutrientMaster::id).toList()
                : new ArrayList<>(units.keySet());
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : ids) {
            NutrientMaster master = masters.get(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", key); item.put("name", master.nameKo()); item.put("unit", units.getOrDefault(key, master.baseUnit()));
            item.put("nameEn", master.nameEn()); item.put("category", master.category()); item.put("baseUnit", master.baseUnit());
            item.put("prioritySupported", master.prioritySupported()); item.put("analyzed", units.containsKey(key));
            items.add(item);
        }
        return items;
    }

    public String standardVersion(StandardProfileId id) { return registry.profile(id).version(); }

    // ---------------------------------------------------------------- 생애주기·열량

    private void validate(AnalysisRequest request, Calc c) {
        int count = request.safeItems().size() + request.safeManualItems().size();
        if (count == 0) throw new AnalysisException("분석할 사료 또는 영양제를 하나 이상 추가해주세요.");
        if (count > 20) throw new AnalysisException("제품은 최대 20개까지 분석할 수 있습니다.");
        Profile profile = request.profile();
        if (c.profile.legacy()) {
            double days = fediafDays(request, c);
            if (days < c.p("minimumAgeDays")) throw new AnalysisException("8주 미만 개체는 현재 지원하지 않습니다.");
            // FEDIAF 성장기 열량식은 예상 성체 체중을 쓴다. NIAS는 체급(adultSize)을 성장곡선 계산에서 검사한다.
            if (profile.species() == Species.DOG && days < c.p("adultAgeDays") &&
                    (profile.expectedAdultWeightKg() == null || profile.expectedAdultWeightKg() < profile.weightKg()))
                throw new AnalysisException("12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.");
        } else if (niasWeeks(request, c) < c.p("minimumAgeWeeks" + title(profile.species()))) {
            throw new AnalysisException("8주 미만 개체는 현재 지원하지 않습니다.");
        }
    }

    private static double fediafDays(AnalysisRequest request, Calc c) {
        Age age = request.profile().age();
        return age.value() * (age.unit() == AgeUnit.WEEK ? 7 : c.p("daysPerMonth"));
    }

    private static double niasWeeks(AnalysisRequest request, Calc c) {
        Age age = request.profile().age();
        return age.unit() == AgeUnit.WEEK ? age.value() : age.value() * c.p("weeksPerMonth");
    }

    private static String lifeStage(AnalysisRequest request, Calc c) {
        Species species = request.profile().species();
        if (c.profile.legacy()) {
            double days = fediafDays(request, c);
            if (species == Species.CAT) return days < c.p("adultAgeDays") ? "GROWTH" : "ADULT";
            if (days < c.p("dogEarlyGrowthDays")) return "GROWTH_EARLY";
            return days < c.p("adultAgeDays") ? "GROWTH_LATE" : "ADULT";
        }
        double weeks = niasWeeks(request, c), months = weeks / c.p("weeksPerMonth");
        if (species == Species.DOG) {
            if (weeks < c.p("dogEarlyGrowthWeeks")) return "DOG_GROWTH_EARLY";
            return months < c.p("adultMonths") ? "DOG_GROWTH_LATE" : "DOG_ADULT";
        }
        if (months <= c.p("catGrowthStage1Months")) return "CAT_GROWTH_1";
        if (months <= c.p("catGrowthStage2Months")) return "CAT_GROWTH_2";
        return months < c.p("adultMonths") ? "CAT_GROWTH_3" : "CAT_ADULT";
    }

    private static Energy energy(AnalysisRequest request, String stage, Calc c) {
        Profile profile = request.profile();
        double exponent = c.p("metabolicExponent" + title(profile.species()));
        double factor;
        Map<String, Object> growth = null;
        if (c.profile.legacy()) {
            if (profile.species() == Species.DOG && !stage.equals("ADULT"))
                factor = c.p("puppyEnergyIntercept") - c.p("puppyEnergySlope") * (profile.weightKg() / profile.expectedAdultWeightKg());
            else factor = profile.species() == Species.DOG ? c.p("energyKDogAdult") : stage.equals("ADULT") ? c.p("energyKCatAdult") : c.p("energyKCatGrowth");
        } else if (stage.startsWith("DOG_GROWTH")) {
            growth = puppyGrowth(request, c);
            factor = (double) growth.get("energyFactor");
        } else if (stage.startsWith("CAT_GROWTH_")) {
            factor = c.p("energyKCatGrowth" + stage.charAt(stage.length() - 1));
        } else {
            factor = c.p(profile.species() == Species.DOG ? "energyKDogAdult" : "energyKCatAdult");
        }
        return new Energy(factor, factor * Math.pow(profile.weightKg(), exponent), growth);
    }

    private static Map<String, Object> puppyGrowth(AnalysisRequest request, Calc c) {
        String size = request.profile().adultSize();
        GrowthCurve curve = size == null ? null : c.profile.growthCurves().get(size);
        if (curve == null) throw new AnalysisException("12개월 미만 개는 예상 성체 체급(adultSize: S, M, L, XL, XXL)이 필요합니다.");
        double weeks = niasWeeks(request, c);
        double percent = Math.min(c.growth(size, "a", curve.a()) * Math.log(weeks) - c.growth(size, "b", curve.b()), 100.0);
        if (percent <= 0) throw new AnalysisException("성장곡선 비율을 계산할 수 없는 주령입니다.");
        double raw = request.profile().weightKg() / (percent / 100.0);
        double adult = Math.max(raw, curve.adultWeightKgAbove());
        if (curve.adultWeightKgAtMost() != null) adult = Math.min(adult, curve.adultWeightKgAtMost());
        boolean outOfRange = raw < curve.adultWeightKgAbove() || (curve.adultWeightKgAtMost() != null && raw > curve.adultWeightKgAtMost());
        Map<String, Object> growth = new LinkedHashMap<>();
        growth.put("sizeClass", size); growth.put("percentOfAdult", percent);
        growth.put("expectedAdultWeightRawKg", raw); growth.put("expectedAdultWeightKg", adult);
        growth.put("energyFactor", c.p("puppyEnergyIntercept") - c.p("puppyEnergySlopePerPercent") * percent);
        growth.put("largeBreedCalcium", adult >= c.p("largeBreedAdultWeightKg"));
        growth.put("outOfRange", outOfRange);
        return growth;
    }

    // ---------------------------------------------------------------- 기준선

    /** 성분별 하루 하한·주의·상한과 출처. 미검증 값은 플래그가 꺼져 있으면 판정에서 뺀다. */
    private Lines lines(AnalysisRequest request, String stage, Energy energy, Calc c) {
        StandardProfile profile = c.profile;
        Profile animal = request.profile();
        String species = animal.species().name();
        double kcal = energy.kcal();
        double cautionRatio = c.p("cautionRatio" + title(animal.species()));
        String recordStage = profile.stage(species, stage).recordStage();
        Map<String, Limit> limits = new LinkedHashMap<>();
        Map<String, Map<String, Object>> references = new LinkedHashMap<>();
        for (ProfileNutrient item : profile.nutrients()) {
            StandardRecord record = registry.record(profile.id(), item.id(), recordStage);
            Double minimumRef = record.min(), upperRef = record.max();
            // 기준표 대신 쓰는 각주 값(매개변수). 값은 레코드와 같은 단위다.
            List<String> overrides = List.of();
            if (profile.legacy()) {
                // FEDIAF 2025 footnote b: 예상 성체 15 kg 초과인 개는 6개월까지 후기 성장기 칼슘 2.5 g/1000 kcal를 적용한다.
                if (item.id().equals("CALCIUM") && animal.species() == Species.DOG && stage.equals("GROWTH_LATE")
                        && animal.expectedAdultWeightKg() > c.p("largeBreedAdultWeightKg") && fediafDays(request, c) < c.p("largeBreedAgeDays")) {
                    minimumRef = c.p("largeBreedCalciumMin"); upperRef = c.p("largeBreedCalciumMax");
                    overrides = List.of("largeBreedCalciumMin", "largeBreedCalciumMax");
                }
            } else if (item.id().equals("CALCIUM") && energy.growth() != null && Boolean.TRUE.equals(energy.growth().get("largeBreedCalcium"))) {
                upperRef = c.p("largeBreedCalciumMax");
                overrides = List.of("largeBreedCalciumMax");
            }
            double factor = 1.0;
            if (!record.unit().equals(item.unit())) factor = UnitConversion.factor(record.unit(), item.unit(), registry.master(profile.id(), item.id()));
            Double minimum = scale(minimumRef, factor, kcal), upper = scale(upperRef, factor, kcal);
            Double caution = upper == null ? null : upper * cautionRatio;
            boolean unverified = record.verificationStatus() == VerificationStatus.UNVERIFIED
                    || overrides.stream().anyMatch(name -> profile.params().get(name).verificationStatus() == VerificationStatus.UNVERIFIED);
            boolean applied = !unverified || registry.allowUnverifiedStandards();
            if (!applied) minimum = caution = upper = null;
            limits.put(item.id(), new Limit(minimum, caution, upper, !applied));
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("sourceDocument", record.sourceDocument()); reference.put("sourceTable", record.sourceTable());
            reference.put("sourceFootnote", overrides.isEmpty() ? record.sourceFootnote() : profile.params().get(overrides.get(0)).source());
            reference.put("unit", item.unit()); reference.put("basis", record.basis());
            reference.put("verificationStatus", record.verificationStatus().name());
            reference.put("verifiedAt", record.verifiedAt() == null ? null : record.verifiedAt().toString());
            reference.put("appliedInJudgement", applied);
            references.put(item.id(), reference);
        }
        return new Lines(limits, references);
    }

    private static Double scale(Double value, double factor, double kcal) {
        if (value == null) return null;
        return kcal / 1000 * (factor != 1.0 ? value * factor : value);
    }

    /** 미검증 → 기준 없음 → 부족 → 과다 → 주의 → 적정. */
    static String classify(double total, Limit line) {
        if (line.unverified()) return UNVERIFIED_STANDARD;
        if (line.minimum() == null && line.upper() == null) return "NO_STANDARD";
        if (line.minimum() != null && total < line.minimum()) return "DEFICIENT";
        if (line.upper() != null && total > line.upper()) return "EXCESS";
        if (line.caution() != null && total >= line.caution()) return "CAUTION";
        return line.upper() != null ? "ADEQUATE" : "ADEQUATE_NO_UPPER_LIMIT";
    }

    private String calciumPhosphorusStatus(AnalysisRequest request, double calcium, double phosphorus, Calc c) {
        if (!c.profile.legacy()) {
            List<String> bounds = List.of("caPMin", "caPMax");
            if (!registry.allowUnverifiedStandards() && bounds.stream().anyMatch(name -> c.profile.params().get(name).verificationStatus() == VerificationStatus.UNVERIFIED)) {
                bounds.forEach(name -> c.used.put(name, c.profile.params().get(name)));
                return UNVERIFIED_STANDARD;
            }
            if (phosphorus == 0) return "UNAVAILABLE";
            double value = calcium / phosphorus;
            return value < c.p("caPMin") ? "LOW" : value > c.p("caPMax") ? "HIGH" : "ADEQUATE";
        }
        if (phosphorus == 0) return "UNAVAILABLE"; double value = calcium / phosphorus; String stage = lifeStage(request, c); double upper;
        if (stage.equals("GROWTH_EARLY")) upper = c.p("caPUpperGrowthEarly");
        else if (stage.equals("GROWTH_LATE")) upper = request.profile().expectedAdultWeightKg() <= c.p("largeBreedAdultWeightKg") || fediafDays(request, c) >= c.p("largeBreedAgeDays") ? c.p("caPUpperGrowthLate") : c.p("caPUpperGrowthLateLargeBreed");
        else upper = c.p("caPUpperAdult");
        return value < c.p("caPMin") ? "LOW" : value > upper ? "HIGH" : "ADEQUATE";
    }

    // ---------------------------------------------------------------- 합산

    /** 제품 1회 제공량 함량을 프로필 단위로 바꾼다. 같은 단위면 값을 그대로 쓴다. */
    private Map<String, Double> productAmounts(CatalogService.Product product, StandardProfile profile, List<String> warnings) {
        Map<String, String> units = new LinkedHashMap<>();
        profile.nutrients().forEach(item -> units.put(item.id(), item.unit()));
        Map<String, Double> result = new LinkedHashMap<>();
        product.nutrients().forEach((id, value) -> {
            if (!units.containsKey(id)) {
                if (warnings != null) warnings.add(product.name() + "의 " + id + "는 " + profile.displayName() + " 성분에 대응하지 않아 제외했습니다.");
                return;
            }
            String source = registry.catalog().catalogUnits().get(id);
            if (units.get(id).equals(source)) { result.put(id, value); return; }
            Double factor = source == null ? null : UnitConversion.factor(source, units.get(id), registry.master(profile.id(), id));
            if (factor == null) {
                if (warnings != null) warnings.add(product.name() + "의 " + id + "(" + source + ")는 " + units.get(id) + "로 환산할 근거가 없어 제외했습니다.");
                return;
            }
            result.put(id, value * factor);
        });
        return result;
    }

    private Aggregate aggregate(AnalysisRequest request, Map<String, Limit> limits, StandardProfile profile) {
        Map<String, Total> totals = new LinkedHashMap<>(); profile.nutrients().forEach(item -> totals.put(item.id(), new Total()));
        Map<String, String> units = new LinkedHashMap<>(); profile.nutrients().forEach(item -> units.put(item.id(), item.unit()));
        boolean estimated = false; List<String> warnings = new ArrayList<>(); List<Map<String, Object>> contributions = new ArrayList<>();
        for (FeedingItem item : request.safeItems()) {
            CatalogService.Product product = catalog.require(item.productId());
            if (!item.unit().equalsIgnoreCase(product.servingUnit())) throw new AnalysisException(product.name() + "의 급여 단위는 " + product.servingUnit() + "입니다.");
            double ratio = item.dailyAmount() / product.servingAmount(); Map<String, Double> values = new LinkedHashMap<>();
            if (product.type().equals("FEED") && product.nutrients().isEmpty() && request.profile().usesCompleteFeed()) {
                for (String id : totals.keySet()) if (limits.get(id).minimum() != null) { totals.get(id).feed += limits.get(id).minimum(); totals.get(id).source = "ESTIMATED"; values.put(id, limits.get(id).minimum()); }
                if (!values.isEmpty() || profile.legacy()) {
                    estimated = true;
                    warnings.add("사료 상세 성분이 없어 최소 권장량으로 추정했습니다.");
                } else {
                    contributions.add(contribution(product.name(), product.type(), "UNKNOWN", values));
                    warnings.add("적용 가능한 최소 권장량이 없어 사료 몫을 추정하지 못했습니다(0으로 계산).");
                    continue;
                }
            } else {
                productAmounts(product, profile, warnings).forEach((id, amount) -> { double applied = amount * ratio; totals.get(id).add(product.type(), applied); values.put(id, applied); });
                if (product.type().equals("FEED") && product.nutrients().isEmpty()) warnings.add("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.");
            }
            contributions.add(contribution(product.name(), product.type(), estimated && product.nutrients().isEmpty() ? "ESTIMATED" : "ACTUAL", values));
        }
        for (ManualItem item : request.safeManualItems()) {
            double ratio = item.dailyAmount() / item.servingAmount(); Set<String> seen = new HashSet<>(); Map<String, Double> values = new LinkedHashMap<>();
            for (ManualNutrient nutrient : item.nutrients()) {
                if (!seen.add(nutrient.nutrientId())) throw new AnalysisException("중복 성분입니다: " + nutrient.nutrientId());
                String unit = units.get(nutrient.nutrientId());
                if (unit == null) throw new AnalysisException("지원하지 않는 성분입니다: " + nutrient.nutrientId());
                double applied;
                if (profile.legacy()) applied = convert(nutrient.amount(), nutrient.unit(), unit) * ratio;
                else {
                    String source = nutrient.unit().toUpperCase(Locale.ROOT);
                    Double factor = UnitConversion.factor(source, unit, registry.master(profile.id(), nutrient.nutrientId()));
                    if (factor == null) throw new AnalysisException("변환할 수 없는 단위입니다: " + source + " → " + unit);
                    applied = nutrient.amount() * factor * ratio;
                }
                totals.get(nutrient.nutrientId()).add(item.type().name(), applied); values.put(nutrient.nutrientId(), applied);
            }
            contributions.add(contribution(item.name(), item.type().name(), "ACTUAL", values));
        }
        return new Aggregate(totals, estimated, warnings, contributions);
    }

    private static Map<String, Object> contribution(String name, String type, String source, Map<String, Double> nutrients) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("name", name); value.put("type", type); value.put("source", source); value.put("nutrients", nutrients); return value;
    }
    private static double convert(double value, String source, String target) {
        Double from = UNIT.get(source.toUpperCase(Locale.ROOT)), to = UNIT.get(target.toUpperCase(Locale.ROOT));
        if (from == null || to == null) throw new AnalysisException("변환할 수 없는 단위입니다: " + source + " → " + target);
        return value * from / to;
    }

    // ---------------------------------------------------------------- 응답 조각

    private Map<String, Object> standardBlock(StandardProfile profile, Map<String, Map<String, Object>> references, Calc c) {
        List<String> statuses = references.values().stream().map(reference -> reference.get("verificationStatus").toString()).toList();
        List<String> unverifiedParameters = c.used.entrySet().stream()
                .filter(entry -> entry.getValue().verificationStatus() == VerificationStatus.UNVERIFIED).map(Map.Entry::getKey).sorted().toList();
        boolean allow = registry.allowUnverifiedStandards();
        List<String> warnings = new ArrayList<>();
        if (statuses.contains("UNVERIFIED") || !unverifiedParameters.isEmpty())
            warnings.add("미검증 프로필: " + profile.displayName() + "에는 원문 대조 전 기준값·매개변수가 포함되어 있습니다.");
        if (statuses.contains("UNVERIFIED") && !allow) warnings.add("원문 대조 전 기준값은 판정과 추천 점수에 쓰지 않았습니다(UNVERIFIED_STANDARD).");
        if (allow) warnings.add("ALLOW_UNVERIFIED_STANDARDS가 켜져 있어 미검증 기준값을 판정에 사용했습니다. 테스트·개발용 결과입니다.");
        Map<String, Object> block = identity(profile);
        Map<String, Object> verification = counts(statuses);
        verification.put("unverifiedParameters", unverifiedParameters);
        block.put("verification", verification);
        block.put("warnings", warnings);
        return block;
    }

    private Map<String, Object> identity(StandardProfile profile) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", profile.id().name()); block.put("displayName", profile.displayName()); block.put("version", profile.version());
        block.put("documentName", profile.documentName()); block.put("publisher", profile.publisher()); block.put("basis", profile.basis());
        block.put("allowUnverifiedStandards", registry.allowUnverifiedStandards());
        return block;
    }

    private static Map<String, Object> counts(List<String> statuses) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("verified", Collections.frequency(statuses, "VERIFIED"));
        counts.put("unverified", Collections.frequency(statuses, "UNVERIFIED"));
        counts.put("notApplicable", Collections.frequency(statuses, "NOT_APPLICABLE"));
        return counts;
    }

    private static String title(Species species) { return species == Species.DOG ? "Dog" : "Cat"; }
    private static String fingerprint(Object request) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest, 0, 8); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Double asDouble(Object value) { return value == null ? null : ((Number) value).doubleValue(); }

    /** 계산에 실제로 쓴 매개변수를 기록해 응답의 검증 요약에 남긴다. */
    private static final class Calc {
        final StandardProfile profile;
        final Map<String, Param> used = new LinkedHashMap<>();
        Calc(StandardProfile profile) { this.profile = profile; }
        double p(String name) { used.put(name, profile.params().get(name)); return profile.param(name); }
        double growth(String size, String name, Param param) { used.put("growthCurves." + size + "." + name, param); return param.value(); }
    }

    record Limit(Double minimum, Double caution, Double upper, boolean unverified) {}
    private record Lines(Map<String, Limit> limits, Map<String, Map<String, Object>> references) {}
    private record Energy(double factor, double kcal, Map<String, Object> growth) {}
    private record Offer(CatalogService.Product product, Map<String, Double> amounts) {}
    private static final class Total { double feed; double supplement; String source = "ACTUAL"; void add(String type, double value) { if (type.equals("FEED")) feed += value; else supplement += value; } }
    private record Aggregate(Map<String, Total> totals, boolean estimated, List<String> warnings, List<Map<String, Object>> contributions) {}
    private record Candidate(double score, long fixed, CatalogService.Product product, Map<String, Double> projected, Map<String, String> statuses) implements Comparable<Candidate> {
        public int compareTo(Candidate other) { int scoreResult = Double.compare(score, other.score); return scoreResult != 0 ? scoreResult : other.product.name().compareTo(product.name()); }
    }
}
