package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.standards.Limit;
import kr.co.wooaeyoung.standards.NutrientMeta;
import kr.co.wooaeyoung.standards.NutritionStandardStrategy;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class NutritionService {
    private static final Map<String, Double> UNIT = Map.of("UG", .001, "MG", 1d, "G", 1000d);

    private final CatalogService catalog;
    private final Map<StandardProfile, NutritionStandardStrategy> strategies;

    public NutritionService(CatalogService catalog, List<NutritionStandardStrategy> strategies) {
        this.catalog = catalog;
        Map<StandardProfile, NutritionStandardStrategy> byProfile = new EnumMap<>(StandardProfile.class);
        for (NutritionStandardStrategy strategy : strategies)
            if (byProfile.put(strategy.profile(), strategy) != null) throw new IllegalStateException("기준 프로필 전략이 중복되었습니다: " + strategy.profile());
        for (StandardProfile profile : StandardProfile.values())
            if (!byProfile.containsKey(profile)) throw new IllegalStateException("기준 프로필 전략이 없습니다: " + profile);
        this.strategies = Map.copyOf(byProfile);
    }

    public Map<String, Object> analyze(AnalysisRequest request) {
        NutritionStandardStrategy strategy = strategy(request.standardProfileOrDefault());
        Map<String, NutrientMeta> meta = meta(strategy);
        validate(request, strategy);
        Profile profile = request.profile();
        String stage = strategy.lifeStage(profile);
        Double kcal = strategy.energy(profile, stage);
        Map<String, Limit> limits = strategy.thresholds(profile, stage, kcal);
        Aggregate aggregate = aggregate(request, limits, meta, strategy.feedFactor());
        List<Map<String, Object>> nutrients = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("deficient", 0); summary.put("adequate", 0); summary.put("caution", 0);
        summary.put("excess", 0); summary.put("noStandard", 0);
        Map<String, Double> current = new LinkedHashMap<>();
        for (String id : meta.keySet()) {
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
            row.put("nutrientId", id); row.put("name", meta.get(id).name()); row.put("unit", meta.get(id).unit());
            row.put("fromFeed", total.feed); row.put("fromSupplements", total.supplement); row.put("source", total.source);
            row.put("total", value); row.put("minimum", line.minimum()); row.put("caution", line.caution()); row.put("upper", line.upper()); row.put("status", status);
            nutrients.add(row);
        }
        Map<String, Object> ratios = new LinkedHashMap<>();
        boolean calciumPhosphorusAvailable = current.containsKey("CALCIUM") && current.containsKey("PHOSPHORUS");
        // 기존 ratios는 고양이에서 빈 객체로 유지하고(하위 호환), 적용 여부는 새 필드로 알린다.
        String calciumPhosphorusApplicability = profile.species() != Species.DOG ? "NOT_APPLICABLE" : calciumPhosphorusAvailable ? "APPLICABLE" : "UNAVAILABLE";
        if (profile.species() == Species.DOG && calciumPhosphorusAvailable) {
            double phosphorus = current.get("PHOSPHORUS");
            Double ratio = phosphorus == 0 ? null : current.get("CALCIUM") / phosphorus;
            ratios.put("calciumPhosphorus", Map.of("value", ratio == null ? 0d : ratio,
                    "status", strategy.calciumPhosphorusStatus(profile, current.get("CALCIUM"), phosphorus)));
        }
        List<String> warnings = new ArrayList<>(aggregate.warnings());
        warnings.addAll(strategy.analysisWarnings(profile, stage));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", fingerprint(request) + "-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("standardVersion", strategy.profile().version());
        result.put("standardSource", strategy.standardSource());
        result.put("cautionPolicy", strategy.cautionPolicy(profile.species()));
        result.put("lifeStage", stage); result.put("referenceEnergyKcal", kcal); result.put("usesEstimatedFeed", aggregate.estimated());
        result.put("summary", summary); result.put("nutrients", nutrients); result.put("contributions", aggregate.contributions());
        result.put("ratios", ratios); result.put("warnings", warnings);
        putProfileFields(result, strategy);
        result.put("ratioApplicability", Map.of("calciumPhosphorus", calciumPhosphorusApplicability));
        return result;
    }

    public Map<String, Object> recommend(RecommendationRequest request) {
        AnalysisRequest analysisRequest = request.analysisRequest();
        NutritionStandardStrategy strategy = strategy(analysisRequest.standardProfileOrDefault());
        Map<String, NutrientMeta> meta = meta(strategy);
        Map<String, Object> base = analyze(analysisRequest);
        String unsupported = strategy.recommendationUnsupportedReason();
        if (unsupported != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("traceId", base.get("traceId")); result.put("standardVersion", strategy.profile().version());
            result.put("message", "현재 기준 프로필에서는 추천 후보를 계산하지 않습니다.");
            result.put("items", List.of()); result.put("excluded", List.of()); result.put("usesEstimatedFeed", base.get("usesEstimatedFeed"));
            putProfileFields(result, strategy); result.put("warnings", List.of(unsupported));
            return result;
        }
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
        for (int round = 0; round < request.requestedMaxItems(strategy.defaultMaxItems()); round++) {
            Map<String, String> roundOriginal = original;
            Candidate best = null;
            for (CatalogService.Product product : remaining) {
                double servingRatio = product.recommendedDailyAmount() / product.servingAmount();
                Map<String, Double> projected = new LinkedHashMap<>(current);
                product.nutrients().forEach((id, amount) -> projected.computeIfPresent(id, (key, value) -> value + amount * servingRatio));
                Map<String, String> statuses = new LinkedHashMap<>();
                projected.forEach((id, value) -> statuses.put(id, classify(value, limits.get(id))));
                List<String> harmful = product.nutrients().keySet().stream()
                        .filter(id -> Set.of("CAUTION", "EXCESS").contains(statuses.get(id))).map(id -> meta.get(id).name()).toList();
                List<String> reasons = new ArrayList<>(harmful);
                if (analysisRequest.profile().species() == Species.DOG &&
                        (!Collections.disjoint(product.nutrients().keySet(), Set.of("CALCIUM", "PHOSPHORUS")))) {
                    String ratioStatus = strategy.calciumPhosphorusStatus(analysisRequest.profile(), projected.get("CALCIUM"), projected.get("PHOSPHORUS"));
                    if (Set.of("LOW", "HIGH").contains(ratioStatus)) reasons.add("칼슘:인 비율");
                }
                if (!reasons.isEmpty()) {
                    excluded.put(product.id(), Map.of("productId", product.id(), "name", product.name(), "reason", "추가 후 기준 이탈 예상: " + String.join(", ", reasons)));
                    continue;
                }
                long fixed = roundOriginal.entrySet().stream().filter(entry -> entry.getValue().equals("DEFICIENT") && !statuses.get(entry.getKey()).equals("DEFICIENT")).count();
                long overlap = product.nutrients().keySet().stream().filter(id -> !Set.of("DEFICIENT", "NO_STANDARD").contains(roundOriginal.get(id))).count();
                double score = strategy.recommendationScore(fixed, overlap);
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
        result.put("traceId", base.get("traceId")); result.put("standardVersion", strategy.profile().version());
        result.put("message", summary.get("deficient") == 0 ? "적용 가능한 기준에서 주의·과다가 검출되지 않은 후보입니다." : "부족 성분, 기준선까지의 여유, 중복 성분을 함께 고려했습니다.");
        result.put("items", selected); result.put("excluded", new ArrayList<>(excluded.values())); result.put("usesEstimatedFeed", base.get("usesEstimatedFeed"));
        putProfileFields(result, strategy); result.put("warnings", List.of());
        return result;
    }

    public List<Map<String, String>> nutrients() {
        return strategy(StandardProfile.DEFAULT).nutrients().stream().map(n -> Map.of("id", n.id(), "name", n.name(), "unit", n.unit())).toList();
    }

    private NutritionStandardStrategy strategy(StandardProfile profile) { return strategies.get(profile); }

    private static Map<String, NutrientMeta> meta(NutritionStandardStrategy strategy) {
        Map<String, NutrientMeta> meta = new LinkedHashMap<>();
        strategy.nutrients().forEach(nutrient -> meta.put(nutrient.id(), nutrient));
        return meta;
    }

    private static void putProfileFields(Map<String, Object> result, NutritionStandardStrategy strategy) {
        result.put("standardProfile", strategy.profile().name());
        result.put("parameterVersion", strategy.parameterVersion());
        result.put("parameterVerification", strategy.parameterVerification());
    }

    private static void validate(AnalysisRequest request, NutritionStandardStrategy strategy) {
        int count = request.safeItems().size() + request.safeManualItems().size();
        if (count == 0) throw new AnalysisException("분석할 사료 또는 영양제를 하나 이상 추가해주세요.");
        if (count > 20) throw new AnalysisException("제품은 최대 20개까지 분석할 수 있습니다.");
        strategy.validate(request.profile());
    }

    private Aggregate aggregate(AnalysisRequest request, Map<String, Limit> limits, Map<String, NutrientMeta> meta, double feedFactor) {
        Map<String, Total> totals = new LinkedHashMap<>(); meta.keySet().forEach(id -> totals.put(id, new Total()));
        boolean estimated = false; List<String> warnings = new ArrayList<>(); List<Map<String, Object>> contributions = new ArrayList<>();
        for (FeedingItem item : request.safeItems()) {
            CatalogService.Product product = catalog.require(item.productId());
            if (!item.unit().equalsIgnoreCase(product.servingUnit())) throw new AnalysisException(product.name() + "의 급여 단위는 " + product.servingUnit() + "입니다.");
            double ratio = item.dailyAmount() / product.servingAmount(); Map<String, Double> values = new LinkedHashMap<>();
            if (product.type().equals("FEED") && product.nutrients().isEmpty() && request.profile().usesCompleteFeed()) {
                estimated = true;
                for (String id : meta.keySet()) if (limits.get(id).minimum() != null) { double assumed = limits.get(id).minimum() * feedFactor; totals.get(id).feed += assumed; totals.get(id).source = "ESTIMATED"; values.put(id, assumed); }
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
                if (!meta.containsKey(nutrient.nutrientId())) throw new AnalysisException("지원하지 않는 성분입니다: " + nutrient.nutrientId());
                double applied = convert(nutrient.amount(), nutrient.unit(), meta.get(nutrient.nutrientId()).unit()) * ratio;
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
    private static String classify(double total, Limit line) {
        if (line.minimum() == null && line.upper() == null) return "NO_STANDARD";
        if (line.minimum() != null && total < line.minimum()) return "DEFICIENT";
        if (line.upper() != null && total > line.upper()) return "EXCESS";
        if (line.caution() != null && total >= line.caution()) return "CAUTION";
        return line.upper() != null ? "ADEQUATE" : "ADEQUATE_NO_UPPER_LIMIT";
    }
    private static String fingerprint(Object request) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest, 0, 8); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Double asDouble(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
    private static final class Total { double feed; double supplement; String source = "ACTUAL"; void add(String type, double value) { if (type.equals("FEED")) feed += value; else supplement += value; } }
    private record Aggregate(Map<String, Total> totals, boolean estimated, List<String> warnings, List<Map<String, Object>> contributions) {}
    private record Candidate(double score, long fixed, CatalogService.Product product, Map<String, Double> projected, Map<String, String> statuses) implements Comparable<Candidate> {
        public int compareTo(Candidate other) { int scoreResult = Double.compare(score, other.score); return scoreResult != 0 ? scoreResult : other.product.name().compareTo(product.name()); }
    }
}
