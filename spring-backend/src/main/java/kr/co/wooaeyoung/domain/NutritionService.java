package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class NutritionService {
    public static final String STANDARD_VERSION = "FEDIAF-2025.09";
    private static final List<String> IDS = List.of("CALCIUM", "PHOSPHORUS", "VITAMIN_D", "VITAMIN_E", "OMEGA3", "ZINC");
    private static final Map<String, Map<String, String>> META = Map.of(
            "CALCIUM", Map.of("name", "칼슘", "unit", "MG"),
            "PHOSPHORUS", Map.of("name", "인", "unit", "MG"),
            "VITAMIN_D", Map.of("name", "비타민 D", "unit", "UG"),
            "VITAMIN_E", Map.of("name", "비타민 E", "unit", "MG"),
            "OMEGA3", Map.of("name", "오메가3", "unit", "MG"),
            "ZINC", Map.of("name", "아연", "unit", "MG"));
    private static final Map<String, Double> UNIT = Map.of("UG", .001, "MG", 1d, "G", 1000d);
    private static final Map<String, Map<String, Map<String, Standard>>> STANDARDS = standards();

    private final CatalogService catalog;
    public NutritionService(CatalogService catalog) { this.catalog = catalog; }

    public Map<String, Object> analyze(AnalysisRequest request) {
        validate(request);
        String stage = lifeStage(request);
        double kcal = energy(request, stage);
        Map<String, Limit> limits = thresholds(request, stage, kcal);
        Aggregate aggregate = aggregate(request, limits);
        List<Map<String, Object>> nutrients = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("deficient", 0); summary.put("adequate", 0); summary.put("caution", 0);
        summary.put("excess", 0); summary.put("noStandard", 0);
        Map<String, Double> current = new LinkedHashMap<>();
        for (String id : IDS) {
            Total total = aggregate.totals().get(id);
            double value = total.feed + total.supplement;
            current.put(id, value);
            Limit line = limits.get(id);
            String status = classify(value, line);
            String bucket = switch (status) {
                case "DEFICIENT" -> "deficient"; case "CAUTION" -> "caution";
                case "EXCESS" -> "excess"; case "NO_STANDARD" -> "noStandard"; default -> "adequate";
            };
            summary.put(bucket, summary.get(bucket) + 1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nutrientId", id); row.put("name", META.get(id).get("name")); row.put("unit", META.get(id).get("unit"));
            row.put("fromFeed", total.feed); row.put("fromSupplements", total.supplement); row.put("source", total.source);
            row.put("total", value); row.put("minimum", line.minimum); row.put("caution", line.caution); row.put("upper", line.upper); row.put("status", status);
            nutrients.add(row);
        }
        Map<String, Object> ratios = new LinkedHashMap<>();
        if (request.profile().species() == Species.DOG) {
            double phosphorus = current.get("PHOSPHORUS");
            Double ratio = phosphorus == 0 ? null : current.get("CALCIUM") / phosphorus;
            ratios.put("calciumPhosphorus", Map.of("value", ratio == null ? 0d : ratio,
                    "status", calciumPhosphorusStatus(request, current.get("CALCIUM"), phosphorus)));
        }
        List<String> warnings = new ArrayList<>(aggregate.warnings());
        warnings.add("비타민 E와 오메가3는 현재 제품 단위가 공식 기준과 달라 기준 없음으로 표시하며 추천 점수에서 제외합니다.");
        warnings.add("상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 공식 상한을 적용하지 않았다는 뜻입니다.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", fingerprint(request) + "-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("standardVersion", STANDARD_VERSION);
        result.put("standardSource", "FEDIAF Nutritional Guidelines 2025, life-stage values per 1000 kcal ME");
        result.put("cautionPolicy", "서비스 조기 경고 기준: 공식 상한의 " + (request.profile().species() == Species.DOG ? 75 : 50) + "%부터 주의");
        result.put("lifeStage", stage); result.put("referenceEnergyKcal", kcal); result.put("usesEstimatedFeed", aggregate.estimated());
        result.put("summary", summary); result.put("nutrients", nutrients); result.put("contributions", aggregate.contributions());
        result.put("ratios", ratios); result.put("warnings", warnings);
        return result;
    }

    public Map<String, Object> recommend(RecommendationRequest request) {
        AnalysisRequest analysisRequest = request.analysisRequest();
        Map<String, Object> base = analyze(analysisRequest);
        @SuppressWarnings("unchecked") List<Map<String, Object>> nutrientRows = (List<Map<String, Object>>) base.get("nutrients");
        Map<String, Double> current = new LinkedHashMap<>();
        Map<String, Limit> limits = new LinkedHashMap<>();
        Map<String, String> original = new LinkedHashMap<>();
        for (Map<String, Object> row : nutrientRows) {
            String id = row.get("nutrientId").toString();
            current.put(id, ((Number) row.get("total")).doubleValue());
            limits.put(id, new Limit(asDouble(row.get("minimum")), asDouble(row.get("caution")), asDouble(row.get("upper"))));
            original.put(id, row.get("status").toString());
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Map<String, Object>> excluded = new LinkedHashMap<>();
        List<CatalogService.Product> remaining = new ArrayList<>(catalog.all().stream()
                .filter(product -> product.type().equals("SUPPLEMENT") && product.recommendedDailyAmount() != null).toList());
        for (int round = 0; round < request.requestedMaxItems(); round++) {
            Map<String, String> roundOriginal = original;
            Candidate best = null;
            for (CatalogService.Product product : remaining) {
                double servingRatio = product.recommendedDailyAmount() / product.servingAmount();
                Map<String, Double> projected = new LinkedHashMap<>(current);
                product.nutrients().forEach((id, amount) -> projected.computeIfPresent(id, (key, value) -> value + amount * servingRatio));
                Map<String, String> statuses = new LinkedHashMap<>();
                projected.forEach((id, value) -> statuses.put(id, classify(value, limits.get(id))));
                List<String> harmful = product.nutrients().keySet().stream()
                        .filter(id -> Set.of("CAUTION", "EXCESS").contains(statuses.get(id))).map(id -> META.get(id).get("name")).toList();
                List<String> reasons = new ArrayList<>(harmful);
                if (analysisRequest.profile().species() == Species.DOG &&
                        (!Collections.disjoint(product.nutrients().keySet(), Set.of("CALCIUM", "PHOSPHORUS")))) {
                    String ratioStatus = calciumPhosphorusStatus(analysisRequest, projected.get("CALCIUM"), projected.get("PHOSPHORUS"));
                    if (Set.of("LOW", "HIGH").contains(ratioStatus)) reasons.add("칼슘:인 비율");
                }
                if (!reasons.isEmpty()) {
                    excluded.put(product.id(), Map.of("productId", product.id(), "name", product.name(), "reason", "추가 후 기준 이탈 예상: " + String.join(", ", reasons)));
                    continue;
                }
                long fixed = roundOriginal.entrySet().stream().filter(entry -> entry.getValue().equals("DEFICIENT") && !statuses.get(entry.getKey()).equals("DEFICIENT")).count();
                long overlap = product.nutrients().keySet().stream().filter(id -> !Set.of("DEFICIENT", "NO_STANDARD").contains(roundOriginal.get(id))).count();
                double score = 10 * fixed - overlap;
                Candidate candidate = new Candidate(score, fixed, product, projected, statuses);
                if (best == null || candidate.compareTo(best) > 0) best = candidate;
            }
            if (best == null) break;
            CatalogService.Product product = best.product;
            selected.add(Map.of("productId", product.id(), "name", product.name(), "dailyAmount", product.recommendedDailyAmount(),
                    "unit", product.servingUnit(), "score", best.score, "fixedNutrients", best.fixed, "projectedStatuses", best.statuses));
            current = best.projected; original = best.statuses;
            remaining.removeIf(item -> item.id().equals(product.id()));
        }
        @SuppressWarnings("unchecked") Map<String, Integer> summary = (Map<String, Integer>) base.get("summary");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", base.get("traceId")); result.put("standardVersion", STANDARD_VERSION);
        result.put("message", summary.get("deficient") == 0 ? "적용 가능한 기준에서 주의·과다가 검출되지 않은 후보입니다." : "부족 성분, 기준선까지의 여유, 중복 성분을 함께 고려했습니다.");
        result.put("items", selected); result.put("excluded", new ArrayList<>(excluded.values())); result.put("usesEstimatedFeed", base.get("usesEstimatedFeed"));
        return result;
    }

    public List<Map<String, String>> nutrients() {
        return IDS.stream().map(id -> Map.of("id", id, "name", META.get(id).get("name"), "unit", META.get(id).get("unit"))).toList();
    }

    private void validate(AnalysisRequest request) {
        int count = request.safeItems().size() + request.safeManualItems().size();
        if (count == 0) throw new AnalysisException("분석할 사료 또는 영양제를 하나 이상 추가해주세요.");
        if (count > 20) throw new AnalysisException("제품은 최대 20개까지 분석할 수 있습니다.");
        Profile profile = request.profile();
        if (profile.age().days() < 56) throw new AnalysisException("8주 미만 개체는 현재 지원하지 않습니다.");
        if (profile.species() == Species.DOG && profile.age().days() < 365 &&
                (profile.expectedAdultWeightKg() == null || profile.expectedAdultWeightKg() < profile.weightKg()))
            throw new AnalysisException("12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.");
    }

    private Aggregate aggregate(AnalysisRequest request, Map<String, Limit> limits) {
        Map<String, Total> totals = new LinkedHashMap<>(); IDS.forEach(id -> totals.put(id, new Total()));
        boolean estimated = false; List<String> warnings = new ArrayList<>(); List<Map<String, Object>> contributions = new ArrayList<>();
        for (FeedingItem item : request.safeItems()) {
            CatalogService.Product product = catalog.require(item.productId());
            if (!item.unit().equalsIgnoreCase(product.servingUnit())) throw new AnalysisException(product.name() + "의 급여 단위는 " + product.servingUnit() + "입니다.");
            double ratio = item.dailyAmount() / product.servingAmount(); Map<String, Double> values = new LinkedHashMap<>();
            if (product.type().equals("FEED") && product.nutrients().isEmpty() && request.profile().usesCompleteFeed()) {
                estimated = true;
                for (String id : IDS) if (limits.get(id).minimum != null) { totals.get(id).feed += limits.get(id).minimum; totals.get(id).source = "ESTIMATED"; values.put(id, limits.get(id).minimum); }
                warnings.add("사료 상세 성분이 없어 최소 권장량으로 추정했습니다.");
            } else {
                product.nutrients().forEach((id, amount) -> { if (totals.containsKey(id)) { double applied = amount * ratio; totals.get(id).add(product.type(), applied); values.put(id, applied); } });
                if (product.type().equals("FEED") && product.nutrients().isEmpty()) warnings.add("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.");
            }
            contributions.add(contribution(product.name(), product.type(), estimated && product.nutrients().isEmpty() ? "ESTIMATED" : "ACTUAL", values));
        }
        for (ManualItem item : request.safeManualItems()) {
            double ratio = item.dailyAmount() / item.servingAmount(); Set<String> seen = new HashSet<>(); Map<String, Double> values = new LinkedHashMap<>();
            for (ManualNutrient nutrient : item.nutrients()) {
                if (!seen.add(nutrient.nutrientId())) throw new AnalysisException("중복 성분입니다: " + nutrient.nutrientId());
                if (!META.containsKey(nutrient.nutrientId())) throw new AnalysisException("지원하지 않는 성분입니다: " + nutrient.nutrientId());
                double applied = convert(nutrient.amount(), nutrient.unit(), META.get(nutrient.nutrientId()).get("unit")) * ratio;
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
    private static String lifeStage(AnalysisRequest request) {
        double days = request.profile().age().days();
        if (request.profile().species() == Species.CAT) return days < 365 ? "GROWTH" : "ADULT";
        if (days < 98) return "GROWTH_EARLY";
        return days < 365 ? "GROWTH_LATE" : "ADULT";
    }
    private static double energy(AnalysisRequest request, String stage) {
        Profile profile = request.profile(); double factor;
        if (profile.species() == Species.DOG && !stage.equals("ADULT")) factor = 254.1 - 135 * (profile.weightKg() / profile.expectedAdultWeightKg());
        else factor = profile.species() == Species.DOG ? 95 : stage.equals("ADULT") ? 75 : 100;
        return factor * Math.pow(profile.weightKg(), profile.species() == Species.DOG ? .75 : .67);
    }
    private static Map<String, Limit> thresholds(AnalysisRequest request, String stage, double kcal) {
        Map<String, Standard> standards = new LinkedHashMap<>(STANDARDS.get(request.profile().species().name()).get(stage));
        if (request.profile().species() == Species.DOG && stage.equals("GROWTH_LATE") && request.profile().expectedAdultWeightKg() > 15 && request.profile().age().days() < 182.625)
            standards.put("CALCIUM", new Standard(2500d, 4500d));
        double cautionRatio = request.profile().species() == Species.DOG ? .75 : .5; Map<String, Limit> result = new LinkedHashMap<>();
        standards.forEach((id, standard) -> { Double min = scale(standard.minimum, kcal), upper = scale(standard.upper, kcal); result.put(id, new Limit(min, upper == null ? null : upper * cautionRatio, upper)); });
        return result;
    }
    private static Double scale(Double value, double kcal) { return value == null ? null : kcal / 1000 * value; }
    private static String classify(double total, Limit line) {
        if (line.minimum == null && line.upper == null) return "NO_STANDARD";
        if (line.minimum != null && total < line.minimum) return "DEFICIENT";
        if (line.upper != null && total > line.upper) return "EXCESS";
        if (line.caution != null && total >= line.caution) return "CAUTION";
        return line.upper != null ? "ADEQUATE" : "ADEQUATE_NO_UPPER_LIMIT";
    }
    private static String calciumPhosphorusStatus(AnalysisRequest request, double calcium, double phosphorus) {
        if (phosphorus == 0) return "UNAVAILABLE"; double value = calcium / phosphorus; String stage = lifeStage(request); double upper;
        if (stage.equals("GROWTH_EARLY")) upper = 1.6;
        else if (stage.equals("GROWTH_LATE")) upper = request.profile().expectedAdultWeightKg() <= 15 || request.profile().age().days() >= 182.625 ? 1.8 : 1.6;
        else upper = 2;
        return value < 1 ? "LOW" : value > upper ? "HIGH" : "ADEQUATE";
    }
    private static String fingerprint(Object request) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest, 0, 8); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Double asDouble(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
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
    private record Limit(Double minimum, Double caution, Double upper) {}
    private static final class Total { double feed; double supplement; String source = "ACTUAL"; void add(String type, double value) { if (type.equals("FEED")) feed += value; else supplement += value; } }
    private record Aggregate(Map<String, Total> totals, boolean estimated, List<String> warnings, List<Map<String, Object>> contributions) {}
    private record Candidate(double score, long fixed, CatalogService.Product product, Map<String, Double> projected, Map<String, String> statuses) implements Comparable<Candidate> {
        public int compareTo(Candidate other) { int scoreResult = Double.compare(score, other.score); return scoreResult != 0 ? scoreResult : other.product.name().compareTo(product.name()); }
    }
}
