package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.ds.MaxHeap;
import kr.co.wooaeyoung.standards.*;
import kr.co.wooaeyoung.units.Conversion;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;

/**
 * NIAS_V3 추천 A안(BE-V3-013, 계산명세서 10장, 처리기준 9장). 분석 결과를 받아 후보를 거르고 점수를 매겨
 * 최대 힙에서 1위를 고른 뒤 총량을 갱신하고 남은 후보를 다시 계산한다.
 *
 * <ul>
 *   <li>점수 = w2 × 보완 수 + w3 × 안전 여유 − w4 × 중복 수. 가중치와 topN은 nias-v3/parameters.json에서 읽는다.</li>
 *   <li>거르기: 추가 후 판정 대상 성분 중 하나라도 주의·과다면 탈락(이미 주의·과다인 성분은 사유를 따로 둔다).
 *       개는 Ca:P 판정이 막히지 않은 생애주기에서 후보가 칼슘·인을 담으면 비율 이탈도 탈락.</li>
 *   <li>합계가 null이거나 판정이 막힌 성분은 부족으로 세지 않는다. 후보가 그 성분을 담았거나 후보의 그 성분을
 *       환산하지 못했는데 주의선·상한이 있으면 안전 확인 불가로 탈락.</li>
 *   <li>안전 여유: 후보가 담은 판정 대상 성분 중 주의선이 있는 성분마다 (주의 − trial) ÷ (주의 − 하한)의 최솟값.
 *       하한이 없으면 0, trial &lt; 하한이면 1, 대상이 없으면 1.</li>
 *   <li>중복: 후보 성분 ∩ (현재 급여 영양제(수동 입력 포함) 성분 ∪ 이미 고른 제품 성분). 사료는 넣지 않는다.</li>
 *   <li>동점이면 productId 오름차순(참조 구현의 heapq 순서).</li>
 * </ul>
 * NiasV3Strategy.recommendationScore(w3 없음)는 쓰지 않는다. 운영 차단(recommendationUnsupportedReason)은 NutritionService가 먼저 본다.
 */
@Component
public class NiasRecommendationService {
    public static final String ALREADY_FED = "ALREADY_FED", CAUTION = "CAUTION", EXCESS = "EXCESS",
            ALREADY_AT_RISK = "ALREADY_CAUTION_OR_EXCESS", CALCIUM_PHOSPHORUS = "CALCIUM_PHOSPHORUS", UNVERIFIABLE = "UNVERIFIABLE";
    static final String MESSAGE_DEFICITS = "부족 성분을 보완하는 추천 조합입니다.";
    static final String MESSAGE_NO_DEFICITS = "현재 조합에서 부족한 성분은 없습니다. 아래 제품은 추가해도 주의 구간에 들지 않습니다.";
    static final String MESSAGE_NONE = "현재 조합에 추가할 수 있는 제품이 없습니다.";
    static final String REFERENCE_ONLY = "참고값: NIAS-v3 기준값·추천 매개변수는 원문 대조 전이라 추천 결과는 검증되지 않은 참고 정보입니다.";
    private static final Set<String> BLOCKED = Set.of(NutrientLine.UNSUPPORTED, NutrientLine.UNVERIFIED_STANDARD);

    /** 가중치(계산명세서 10.1). */
    public record Weights(double w2, double w3, double w4, int topN) {}

    /**
     * 추천 후보 하나. amounts는 체중별 용량을 적용한 1일 추가량(기준 단위)이고 환산된 성분만 담는다.
     * unconverted는 후보가 담았으나 값을 쓸 수 없는 성분, keys는 중복 계산에 쓰는 성분 키 전체다.
     */
    public record Candidate(String productId, String name, String unit, NiasDose dose,
                            Map<String, Double> amounts, Set<String> unconverted, Set<String> keys) {}

    /**
     * 분석 시점의 성분 상태. totals 값이 null이면 합계를 만들지 못한 성분, statuses는 분석 응답의 status다.
     * calciumPhosphorus는 (칼슘, 인) → LOW/HIGH/ADEQUATE/UNAVAILABLE. 검사하지 않으면 null.
     */
    public record State(Map<String, Double> totals, Map<String, Limit> limits, Map<String, String> statuses,
                        Map<String, String> names, BiFunction<Double, Double, String> calciumPhosphorus) {}

    /** warnings는 추천 계산이 덧붙이는 경고(Ca:P 검사 생략, 이미 주의·과다인 성분). */
    public record Result(String message, List<Map<String, Object>> items, List<Map<String, Object>> excluded, boolean hadDeficits, List<String> warnings) {}

    private final CatalogService catalog;
    private final ProductDataRegistry registry;
    private final Weights weights;
    private final boolean allowUnverified;

    public NiasRecommendationService(CatalogService catalog, ProductDataRegistry registry, StandardParametersLoader loader, StandardsProperties properties) {
        this.catalog = catalog;
        this.registry = registry;
        this.allowUnverified = properties.allowUnverified();
        NiasV3Parameters.Recommendation recommendation = loader.load(StandardProfile.NIAS_V3, NiasV3Parameters.class).recommendation();
        this.weights = new Weights(recommendation.w2(), recommendation.w3(), recommendation.w4(), recommendation.topN());
    }

    public Weights weights() { return weights; }

    /** 운영 경로: 카탈로그 영양제를 후보로 만들고, 현재 급여 중인 제품은 ALREADY_FED로 뺀다. */
    public Result recommend(RecommendationRequest request, Map<String, Object> analysis, NutritionStandardStrategy strategy) {
        Profile profile = request.profile();
        String stage = analysis.get("lifeStage").toString();
        String calciumPhosphorusBlocked = profile.species() == Species.DOG ? strategy.calciumPhosphorusBlockedStatus(stage) : null;
        BiFunction<Double, Double, String> calciumPhosphorus = profile.species() == Species.DOG && calciumPhosphorusBlocked == null
                ? (calcium, phosphorus) -> strategy.calciumPhosphorusStatus(profile, calcium, phosphorus) : null;
        State state = state(analysis, calciumPhosphorus);
        Set<String> fedIds = new HashSet<>(), fedKeys = new HashSet<>();
        for (FeedingItem item : request.analysisRequest().safeItems()) {
            CatalogService.Product product = catalog.require(item.productId());
            fedIds.add(product.id());
            if (product.type().equals("SUPPLEMENT")) fedKeys.addAll(contents(product.id(), profile.species()).keySet());
        }
        for (ManualItem item : request.analysisRequest().safeManualItems())
            if (item.type() == ProductType.SUPPLEMENT) item.nutrients().forEach(nutrient -> fedKeys.add(overlapKey(nutrient.nutrientId())));
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Map<String, Object>> alreadyFed = new LinkedHashMap<>();
        for (CatalogService.Product product : catalog.all()) {
            if (!product.type().equals("SUPPLEMENT") || (product.recommendedDailyAmount() == null && product.doseByWeight().isEmpty())) continue;
            if (fedIds.contains(product.id())) {
                alreadyFed.put(product.id(), excludedEntry(product.id(), product.name(), List.of(reason(ALREADY_FED, List.of())), "현재 급여 중인 제품"));
                continue;
            }
            candidates.add(candidate(product, profile.weightKg(), profile.species()));
        }
        Result ranked = rank(state, candidates, fedKeys, request.requestedMaxItems(weights.topN()), weights);
        List<Map<String, Object>> excluded = new ArrayList<>(alreadyFed.values());
        excluded.addAll(ranked.excluded());
        List<String> warnings = new ArrayList<>();
        if (referenceOnly(analysis, strategy)) warnings.add(REFERENCE_ONLY);
        if (calciumPhosphorusBlocked != null)
            warnings.add("칼슘:인 비율 미적용: 이 생애주기(" + stage + ")는 칼슘:인 비율을 판정하지 않아(" + calciumPhosphorusBlocked + ") 추천에서도 비율로 거르지 않았습니다.");
        warnings.addAll(ranked.warnings());
        return new Result(ranked.message(), ranked.items(), excluded, ranked.hadDeficits(), List.copyOf(warnings));
    }

    /** allow-unverified=true에서 추천 매개변수가 검증 대기이거나 검증 대기 기준값으로 판정한 성분이 있으면 참고값 경고를 맨 앞에 둔다. */
    private boolean referenceOnly(Map<String, Object> analysis, NutritionStandardStrategy strategy) {
        if (!allowUnverified) return false;
        @SuppressWarnings("unchecked") Map<String, Integer> summary = (Map<String, Integer>) analysis.get("verificationSummary");
        return strategy.parameterVerification().get("recommendation") == VerificationStatus.PENDING_VERIFICATION
                || (summary != null && summary.getOrDefault("judgedWithUnverified", 0) > 0);
    }

    /** 분석 응답의 nutrients 행에서 성분 상태를 만든다. */
    static State state(Map<String, Object> analysis, BiFunction<Double, Double, String> calciumPhosphorus) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) analysis.get("nutrients");
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Limit> limits = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>(), names = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = row.get("nutrientId").toString();
            totals.put(id, number(row.get("total")));
            limits.put(id, new Limit(number(row.get("minimum")), number(row.get("caution")), number(row.get("upper"))));
            statuses.put(id, row.get("status").toString());
            names.put(id, row.get("name").toString());
        }
        return new State(totals, limits, statuses, names, calciumPhosphorus);
    }

    /** 제품 하나를 후보로 만든다. 1일 추가량 = 1회 제공량당 값 × (용량 ÷ 1회 제공량), 1 g당 값이면 × 용량(g). */
    Candidate candidate(CatalogService.Product product, double weightKg, Species species) {
        NiasDose dose = NiasDose.of(product, weightKg);
        Map<String, Double> amounts = new LinkedHashMap<>();
        Set<String> unconverted = new LinkedHashSet<>();
        double ratio = dose.dose() / product.servingAmount();
        Map<String, Conversion> contents = contents(product.id(), species);
        contents.forEach((id, conversion) -> {
            if (conversion.converted()) amounts.put(id, conversion.perGram() ? conversion.value() * dose.dose() : conversion.value() * ratio);
            else if (conversion.status() != Conversion.Status.EXCLUDED) unconverted.add(id);   // 종 규칙으로 더하지 않는 성분(고양이 베타카로틴)은 기여가 없다
        });
        Set<String> keys = overlapKeys(contents);
        return new Candidate(product.id(), product.name(), product.servingUnit(), dose, amounts, unconverted, keys);
    }

    /** 종 규칙으로 제외된 성분은 실제 기여가 없으므로 중복 성분과 중복 감점에도 포함하지 않는다. */
    static Set<String> overlapKeys(Map<String, Conversion> contents) {
        Set<String> keys = new LinkedHashSet<>();
        contents.forEach((id, conversion) -> {
            if (conversion.status() != Conversion.Status.EXCLUDED) keys.add(id);
        });
        return keys;
    }

    private Map<String, Conversion> contents(String productId, Species species) {
        return registry.find(productId).orElseThrow(() -> new IllegalStateException("제품 데이터가 적재되지 않았습니다: " + productId)).contents(species);
    }

    /** 라벨의 EPA·DHA·총 오메가3는 정규화 후 EPA_DHA 하나의 키가 되므로 수동 입력도 같은 키로 센다. */
    private static String overlapKey(String id) { return Set.of("EPA", "DHA", "OMEGA3").contains(id) ? "EPA_DHA" : id; }

    /** 탐욕 선택(계산명세서 10.2). 매 라운드 최대 힙을 새로 만들고, 고른 제품을 총량과 중복 키에 더한 뒤 남은 후보를 다시 계산한다. */
    static Result rank(State state, List<Candidate> candidates, Set<String> fedSupplementKeys, int maxItems, Weights weights) {
        Map<String, Double> current = new LinkedHashMap<>(state.totals());
        Map<String, String> statuses = new LinkedHashMap<>(state.statuses());
        Set<String> usedKeys = new HashSet<>(fedSupplementKeys);
        List<Candidate> remaining = new ArrayList<>(candidates);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Map<String, Object>> excluded = new LinkedHashMap<>();
        boolean hadDeficits = state.statuses().entrySet().stream()
                .anyMatch(entry -> judged(state, state.totals(), entry.getKey()) && "DEFICIENT".equals(entry.getValue()));
        for (int round = 0; round < maxItems; round++) {
            MaxHeap<Scored> heap = new MaxHeap<>(Comparator.<Scored>comparingDouble(Scored::score)
                    .thenComparing(Scored::productId, Comparator.reverseOrder()));
            for (Candidate candidate : remaining) {
                Scored scored = evaluate(state, current, statuses, usedKeys, candidate, weights);
                if (scored.reasons().isEmpty()) { heap.push(scored); excluded.remove(candidate.productId()); }
                else excluded.put(candidate.productId(), excludedEntry(candidate.productId(), candidate.name(), scored.reasons(), describe(state, scored.reasons())));
            }
            Scored best = heap.pop();
            if (best == null) break;
            items.add(item(best, weights));
            current = best.projected(); statuses = best.statuses();
            usedKeys.addAll(best.candidate().keys());
            remaining.removeIf(candidate -> candidate.productId().equals(best.productId()));
        }
        String message = items.isEmpty() ? MESSAGE_NONE : hadDeficits ? MESSAGE_DEFICITS : MESSAGE_NO_DEFICITS;
        List<String> atRisk = state.statuses().entrySet().stream()
                .filter(entry -> judged(state, state.totals(), entry.getKey()) && Set.of("CAUTION", "EXCESS").contains(entry.getValue()))
                .map(entry -> state.names().getOrDefault(entry.getKey(), entry.getKey())).toList();
        List<String> warnings = atRisk.isEmpty() ? List.of()
                : List.of("이미 주의·과다인 성분(" + String.join(", ", atRisk) + ")이 있어 추가 후 판정에서 모든 후보가 탈락합니다. 현재 급여량을 먼저 확인하세요.");
        return new Result(message, items, new ArrayList<>(excluded.values()), hadDeficits, warnings);
    }

    private record Scored(Candidate candidate, double score, long fixed, double margin, long overlap,
                          List<String> fixedIds, String marginNutrientId, List<String> overlapIds,
                          Map<String, Double> projected, Map<String, String> statuses, List<Map<String, Object>> reasons) {
        String productId() { return candidate.productId(); }
    }

    /** 성분 합계가 있고 판정이 막히지 않은 성분만 판정·보완·여유 계산에 쓴다. */
    private static boolean judged(State state, Map<String, Double> current, String id) {
        return current.get(id) != null && !BLOCKED.contains(state.statuses().get(id));
    }

    private static Scored evaluate(State state, Map<String, Double> current, Map<String, String> statuses, Set<String> usedKeys,
                                   Candidate candidate, Weights weights) {
        Map<String, Double> projected = new LinkedHashMap<>(current);
        Map<String, String> projectedStatuses = new LinkedHashMap<>(statuses);
        List<String> caution = new ArrayList<>(), excess = new ArrayList<>(), atRisk = new ArrayList<>(), unverifiable = new ArrayList<>();
        for (String id : current.keySet()) {
            Limit limit = state.limits().get(id);
            boolean touches = candidate.amounts().containsKey(id) || candidate.unconverted().contains(id);
            boolean guarded = limit.caution() != null || limit.upper() != null, judged = judged(state, current, id);
            if (touches && guarded && (!judged || candidate.unconverted().contains(id))) unverifiable.add(id);
            if (!judged) continue;
            double trial = current.get(id) + candidate.amounts().getOrDefault(id, 0d);
            projected.put(id, trial);
            String status = NutritionService.classify(trial, limit);
            projectedStatuses.put(id, status);
            if (!status.equals("CAUTION") && !status.equals("EXCESS")) continue;
            String before = statuses.get(id);
            if ("CAUTION".equals(before) || "EXCESS".equals(before)) atRisk.add(id);
            else (status.equals("CAUTION") ? caution : excess).add(id);
        }
        List<Map<String, Object>> reasons = new ArrayList<>();
        if (!excess.isEmpty()) reasons.add(reason(EXCESS, excess));
        if (!caution.isEmpty()) reasons.add(reason(CAUTION, caution));
        if (!atRisk.isEmpty()) reasons.add(reason(ALREADY_AT_RISK, atRisk));
        if (!unverifiable.isEmpty()) reasons.add(reason(UNVERIFIABLE, unverifiable));
        if (state.calciumPhosphorus() != null && (candidate.amounts().containsKey("CALCIUM") || candidate.amounts().containsKey("PHOSPHORUS"))
                && projected.get("CALCIUM") != null && projected.get("PHOSPHORUS") != null
                && judged(state, current, "CALCIUM") && judged(state, current, "PHOSPHORUS")) {
            String ratio = state.calciumPhosphorus().apply(projected.get("CALCIUM"), projected.get("PHOSPHORUS"));
            if ("LOW".equals(ratio) || "HIGH".equals(ratio)) reasons.add(reason(CALCIUM_PHOSPHORUS, List.of("CALCIUM", "PHOSPHORUS"), ratio));
        }
        if (!reasons.isEmpty()) return new Scored(candidate, 0, 0, 0, 0, List.of(), null, List.of(), projected, projectedStatuses, reasons);

        List<String> fixedIds = new ArrayList<>();
        for (String id : current.keySet())
            if (judged(state, current, id) && "DEFICIENT".equals(statuses.get(id)) && !"DEFICIENT".equals(projectedStatuses.get(id))) fixedIds.add(id);
        double margin = 1d;
        String marginNutrientId = null;
        for (String id : candidate.amounts().keySet()) {
            Limit limit = state.limits().get(id);
            if (limit == null || limit.caution() == null || !judged(state, current, id)) continue;
            double lower = limit.minimum() == null ? 0d : limit.minimum(), trial = projected.get(id);
            double value = trial < lower ? 1d : (limit.caution() - trial) / (limit.caution() - lower);
            if (marginNutrientId == null || value < margin) { margin = value; marginNutrientId = id; }
        }
        List<String> overlapIds = candidate.keys().stream().filter(usedKeys::contains).toList();
        double score = weights.w2() * fixedIds.size() + weights.w3() * margin - weights.w4() * overlapIds.size();
        return new Scored(candidate, score, fixedIds.size(), margin, overlapIds.size(), fixedIds, marginNutrientId, overlapIds,
                projected, projectedStatuses, List.of());
    }

    private static Map<String, Object> item(Scored best, Weights weights) {
        Candidate candidate = best.candidate();
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("fixedNutrients", best.fixed()); breakdown.put("margin", best.margin()); breakdown.put("overlap", best.overlap());
        breakdown.put("w2", weights.w2()); breakdown.put("w3", weights.w3()); breakdown.put("w4", weights.w4());
        breakdown.put("fixedScore", weights.w2() * best.fixed()); breakdown.put("marginScore", weights.w3() * best.margin());
        breakdown.put("overlapPenalty", weights.w4() * best.overlap());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", candidate.productId()); item.put("name", candidate.name());
        item.put("dailyAmount", candidate.dose().dose()); item.put("unit", candidate.unit());
        item.put("score", best.score()); item.put("fixedNutrients", best.fixed());
        item.put("margin", best.margin()); item.put("overlap", best.overlap());
        item.put("scoreBreakdown", breakdown);
        item.put("projectedStatuses", best.statuses());
        item.put("fixedNutrientIds", best.fixedIds()); item.put("marginNutrientId", best.marginNutrientId());
        item.put("overlapNutrientIds", best.overlapIds()); item.put("doseBand", candidate.dose().toMap());
        return item;
    }

    private static Map<String, Object> reason(String code, List<String> nutrientIds) { return reason(code, nutrientIds, null); }

    private static Map<String, Object> reason(String code, List<String> nutrientIds, String ratioStatus) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("code", code); reason.put("nutrientIds", List.copyOf(nutrientIds));
        if (ratioStatus != null) reason.put("ratioStatus", ratioStatus);
        return reason;
    }

    /** 기존 excluded 필드(productId, name, reason)를 유지하고 reasonCode·reasons를 더한다. */
    private static Map<String, Object> excludedEntry(String productId, String name, List<Map<String, Object>> reasons, String text) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("productId", productId); entry.put("name", name); entry.put("reason", text);
        entry.put("reasonCode", reasons.get(0).get("code")); entry.put("reasons", reasons);
        return entry;
    }

    private static String describe(State state, List<Map<String, Object>> reasons) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> reason : reasons) {
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) reason.get("nutrientIds");
            String names = String.join(", ", ids.stream().map(id -> state.names().getOrDefault(id, id)).toList());
            parts.add(switch (reason.get("code").toString()) {
                case EXCESS -> "추가 후 과다: " + names;
                case CAUTION -> "추가 후 주의: " + names;
                case ALREADY_AT_RISK -> "이미 주의·과다인 성분: " + names;
                case UNVERIFIABLE -> "합계 또는 제품 값을 환산할 수 없어 안전 확인 불가: " + names;
                case CALCIUM_PHOSPHORUS -> "추가 후 칼슘:인 비율 " + ("LOW".equals(reason.get("ratioStatus")) ? "낮음" : "높음");
                default -> reason.get("code").toString();
            });
        }
        return String.join("; ", parts);
    }

    private static Double number(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
}
