package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.domain.NiasRecommendationService.Candidate;
import kr.co.wooaeyoung.domain.NiasRecommendationService.Result;
import kr.co.wooaeyoung.domain.NiasRecommendationService.State;
import kr.co.wooaeyoung.domain.NiasRecommendationService.Weights;
import kr.co.wooaeyoung.standards.Limit;
import kr.co.wooaeyoung.standards.NiasV3Strategy;
import kr.co.wooaeyoung.standards.NutrientLine;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.function.BiFunction;

import static kr.co.wooaeyoung.domain.NiasRecommendationCasesTest.excluded;
import static org.junit.jupiter.api.Assertions.*;

/** BE-V3-012·013 추천 규칙(Issue #4 결정 A~G). 가중치는 nias-v3/parameters.json 값을 쓴다. */
@SpringBootTest @ActiveProfiles("test")
class NiasRecommendationRulesTest {
    static final NiasDose ONE = NiasRecommendationCasesTest.ONE;

    @Autowired NutritionService nutrition;
    @Autowired NiasRecommendationService recommender;
    @Autowired NiasV3Strategy nias;
    @Autowired CatalogService catalog;

    /** 성분 하나: 합계(null 가능), 하한·주의·상한, 판정을 막는 상태(없으면 null). */
    record N(Double total, Double minimum, Double caution, Double upper, String blocked) {
        static N of(Double total, Double minimum, Double caution, Double upper) { return new N(total, minimum, caution, upper, null); }
    }

    static State state(Map<String, N> nutrients, BiFunction<Double, Double, String> calciumPhosphorus) {
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Limit> limits = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>(), names = new LinkedHashMap<>();
        nutrients.forEach((id, n) -> {
            Limit limit = new Limit(n.minimum(), n.caution(), n.upper());
            totals.put(id, n.total()); limits.put(id, limit); names.put(id, id);
            statuses.put(id, n.blocked() != null ? n.blocked() : n.total() == null ? "INSUFFICIENT_DATA" : NutritionService.classify(n.total(), limit));
        });
        return new State(totals, limits, statuses, names, calciumPhosphorus);
    }

    static Candidate candidate(String id, Map<String, Double> amounts) {
        return new Candidate(id, id, "TABLET", ONE, amounts, Set.of(), amounts.keySet());
    }

    Result rank(State state, List<Candidate> candidates, Set<String> fedKeys) {
        Weights weights = recommender.weights();
        return NiasRecommendationService.rank(state, candidates, fedKeys, weights.topN(), weights);
    }

    static List<Object> ids(Result result) { return result.items().stream().map(item -> item.get("productId")).toList(); }

    // ---------------------------------------------------------------- 거르기

    @Test @DisplayName("추가 후 주의·과다가 되면 탈락하고 사유 코드와 성분을 돌려준다")
    void cautionAndExcessAreExcluded() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("A", N.of(5d, 1d, 8d, 10d));
        Candidate caution = candidate("caution", Map.of("A", 3d)), excess = candidate("excess", Map.of("A", 5.5));   // 8 ≥ 주의 8, 10.5 > 상한 10
        Result result = rank(state(nutrients, null), List.of(caution, excess), Set.of());
        assertTrue(result.items().isEmpty());
        assertEquals(NiasRecommendationService.CAUTION, excluded(result, "caution").get("reasonCode"));
        assertEquals(NiasRecommendationService.EXCESS, excluded(result, "excess").get("reasonCode"));
        assertTrue(excluded(result, "excess").get("reason").toString().contains("추가 후 과다: A"));
        // ok(A 7.9)를 고른 뒤 다시 계산하면 caution 후보는 10.9가 되어 과다로 바뀐다. 사유는 마지막 라운드 기준이다.
        Result reevaluated = rank(state(nutrients, null), List.of(caution, excess, candidate("ok", Map.of("A", 2.9))), Set.of());
        assertEquals(List.of("ok"), ids(reevaluated));
        assertEquals(NiasRecommendationService.EXCESS, excluded(reevaluated, "caution").get("reasonCode"));
    }

    @Test @DisplayName("이미 주의·과다인 성분이 있으면 그 성분을 담지 않은 후보도 탈락하고, 사유를 따로 표시한다(결정 B)")
    void preExistingCautionExcludesEveryCandidate() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("A", N.of(9d, 1d, 8d, 10d));    // 이미 주의
        nutrients.put("B", N.of(0d, 1d, null, null));  // 부족
        Result result = rank(state(nutrients, null), List.of(candidate("b", Map.of("B", 2d))), Set.of());
        assertTrue(result.items().isEmpty());
        assertEquals(NiasRecommendationService.MESSAGE_NONE, result.message());
        Map<String, Object> entry = excluded(result, "b");
        assertEquals(NiasRecommendationService.ALREADY_AT_RISK, entry.get("reasonCode"));
        assertEquals(List.of(Map.of("code", NiasRecommendationService.ALREADY_AT_RISK, "nutrientIds", List.of("A"))), entry.get("reasons"));
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("이미 주의·과다인 성분(A)"), result.warnings().toString());
    }

    @Test @DisplayName("개 Ca:P: 후보가 칼슘·인을 담고 추가 후 비율이 낮거나 높으면 탈락, 판정이 막힌 생애주기(검사 함수 null)는 검사하지 않는다")
    void calciumPhosphorusRatio() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("CALCIUM", N.of(1d, 0.5, 5d, 6d));
        nutrients.put("PHOSPHORUS", N.of(1d, 0.5, 5d, 6d));
        BiFunction<Double, Double, String> ratio = (calcium, phosphorus) -> nias.calciumPhosphorusStatus(NiasRecommendationCasesTest.T1, calcium, phosphorus);
        List<Candidate> candidates = List.of(
                candidate("high", Map.of("CALCIUM", 1.5)),       // 2.5 / 1 = 2.5
                candidate("low", Map.of("PHOSPHORUS", 1.5)),     // 1 / 2.5 = 0.4
                candidate("ok", Map.of("CALCIUM", 0.5)));        // 1.5
        Result checked = rank(state(nutrients, ratio), candidates, Set.of());
        assertEquals(List.of("ok"), ids(checked));
        assertEquals("HIGH", ((List<Map<String, Object>>) excluded(checked, "high").get("reasons")).get(0).get("ratioStatus"));
        assertEquals("LOW", ((List<Map<String, Object>>) excluded(checked, "low").get("reasons")).get(0).get("ratioStatus"));
        Result skipped = rank(state(nutrients, null), candidates, Set.of());
        assertEquals(3, skipped.items().size());
    }

    @Test @DisplayName("성장기 강아지는 Ca:P 판정이 막혀 있어(UNSUPPORTED) 추천에서도 Ca:P로 거르지 않는다")
    void dogGrowthSkipsCalciumPhosphorus() {
        Profile puppy = new Profile("검산", Species.DOG, 10, new Age(20, AgeUnit.WEEK), "M", null, true);
        RecommendationRequest request = new RecommendationRequest(puppy, List.of(new FeedingItem("feed-complete-unknown", 150, "G")), List.of(), null, StandardProfile.NIAS_V3);
        Result result = recommender.recommend(request, nutrition.analyze(request.analysisRequest()), nias);
        for (Map<String, Object> entry : result.excluded())
            assertNotEquals(NiasRecommendationService.CALCIUM_PHOSPHORUS, entry.get("reasonCode"), entry.toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("칼슘:인 비율 미적용: 이 생애주기(GROWTH_LATE)")), result.warnings().toString());
    }

    @Test @DisplayName("합계가 null인 성분: 부족에 세지 않고, 주의선·상한이 있는데 후보가 담으면 안전 확인 불가(결정 D)")
    void nullTotalIsUnverifiable() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("GUARDED", N.of(null, 1d, 8d, 10d));
        nutrients.put("OPEN", N.of(null, 1d, null, null));
        nutrients.put("BLOCKED", new N(0d, 1d, 8d, 10d, NutrientLine.UNVERIFIED_STANDARD));
        Result result = rank(state(nutrients, null), List.of(
                candidate("guarded", Map.of("GUARDED", 1d)),
                candidate("blocked", Map.of("BLOCKED", 1d)),
                candidate("open", Map.of("OPEN", 5d))), Set.of());
        assertEquals(List.of("open"), ids(result));
        assertEquals(0L, result.items().get(0).get("fixedNutrients"));          // null 합계는 부족이 아니다
        assertEquals(1.0, (double) result.items().get(0).get("margin"));        // 판정 대상 성분이 없으면 1
        assertFalse(result.hadDeficits());
        assertEquals(NiasRecommendationService.UNVERIFIABLE, excluded(result, "guarded").get("reasonCode"));
        assertEquals(NiasRecommendationService.UNVERIFIABLE, excluded(result, "blocked").get("reasonCode"));
    }

    @Test @DisplayName("후보 성분을 환산하지 못했고 그 성분에 주의선·상한이 있으면 안전 확인 불가(결정 D')")
    void unconvertedCandidateNutrientIsUnverifiable() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("GUARDED", N.of(2d, 1d, 8d, 10d));
        nutrients.put("OPEN", N.of(2d, 1d, null, null));
        Result result = rank(state(nutrients, null), List.of(
                new Candidate("guarded", "guarded", "TABLET", ONE, Map.of(), Set.of("GUARDED"), Set.of("GUARDED")),
                new Candidate("open", "open", "TABLET", ONE, Map.of(), Set.of("OPEN"), Set.of("OPEN"))), Set.of());
        assertEquals(List.of("open"), ids(result));
        assertEquals(List.of(Map.of("code", NiasRecommendationService.UNVERIFIABLE, "nutrientIds", List.of("GUARDED"))),
                excluded(result, "guarded").get("reasons"));
    }

    // ---------------------------------------------------------------- 안전 여유·중복

    @Test @DisplayName("안전 여유: 주의선이 있는 성분(주의선만 있는 성분 포함)의 최솟값, 하한 없으면 0, trial < 하한이면 1(결정 C)")
    void marginRules() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("CAUTION_ONLY", N.of(10d, 10d, 30d, null));   // 아연처럼 주의선만 있음
        nutrients.put("NO_LOWER", N.of(0d, null, 10d, 20d));
        nutrients.put("BELOW_LOWER", N.of(0d, 5d, 10d, 20d));
        nutrients.put("NO_CAUTION", N.of(0d, 1d, null, null));
        Result result = rank(state(nutrients, null), List.of(
                candidate("cautionOnly", Map.of("CAUTION_ONLY", 15d)),   // (30 − 25) ÷ (30 − 10) = 0.25
                candidate("noLower", Map.of("NO_LOWER", 4d)),            // (10 − 4) ÷ (10 − 0) = 0.6
                candidate("belowLower", Map.of("BELOW_LOWER", 2d)),      // 2 < 5 → 1
                candidate("noCaution", Map.of("NO_CAUTION", 0.5))), Set.of());
        Map<String, Map<String, Object>> byId = new HashMap<>();
        result.items().forEach(item -> byId.put(item.get("productId").toString(), item));
        // topN = 3. belowLower·noCaution 5점 동점(productId 순), noLower 3점, cautionOnly 1.25점은 빠진다.
        assertEquals(List.of("belowLower", "noCaution", "noLower"), ids(result));
        assertEquals(1.0, (double) byId.get("belowLower").get("margin"), 1e-12);
        assertEquals("BELOW_LOWER", byId.get("belowLower").get("marginNutrientId"));
        assertEquals(0.6, (double) byId.get("noLower").get("margin"), 1e-12);
        assertEquals(1.0, (double) byId.get("noCaution").get("margin"));
        assertNull(byId.get("noCaution").get("marginNutrientId"));
        // 주의선만 있는 성분은 여유 계산에 들어간다(0.25). 점수 = 5 × 0.25.
        Result cautionOnly = rank(state(nutrients, null), List.of(candidate("cautionOnly", Map.of("CAUTION_ONLY", 15d))), Set.of());
        assertEquals(0.25, (double) cautionOnly.items().get(0).get("margin"), 1e-12);
        assertEquals("CAUTION_ONLY", cautionOnly.items().get(0).get("marginNutrientId"));
        assertEquals(recommender.weights().w3() * 0.25, (double) cautionOnly.items().get(0).get("score"), 1e-12);
    }

    @Test @DisplayName("여유는 평균이 아니라 최솟값이다: 한 성분이 주의선 가까이 가면 다른 성분이 여유로워도 낮다")
    void marginIsMinimumNotAverage() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("A", N.of(0d, 0d, 10d, 20d));
        nutrients.put("B", N.of(0d, 0d, 10d, 20d));
        Result result = rank(state(nutrients, null), List.of(candidate("ab", Map.of("A", 9d, "B", 1d))), Set.of());
        assertEquals(0.1, (double) result.items().get(0).get("margin"), 1e-12);
        assertEquals("A", result.items().get(0).get("marginNutrientId"));
    }

    @Test @DisplayName("중복: 현재 급여 영양제 성분과 이미 고른 제품 성분의 교집합(결정 E), 점수에서 w4 × 중복 수를 뺀다")
    void overlapCountsFedAndChosenKeys() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("A", N.of(5d, 1d, null, null));
        nutrients.put("B", N.of(5d, 1d, null, null));
        Result result = rank(state(nutrients, null), List.of(
                candidate("p1", Map.of("A", 1d)),
                candidate("p2", Map.of("A", 1d, "B", 1d))), Set.of("B"));
        // 1라운드: p1 중복 0(5점), p2 중복 1(B, 4점) → p1. 2라운드: p2 중복 2(A는 p1에서, B는 급여 중) → 3점.
        assertEquals(List.of("p1", "p2"), ids(result));
        assertEquals(5.0, (double) result.items().get(0).get("score"), 1e-12);
        Map<String, Object> second = result.items().get(1);
        assertEquals(2L, second.get("overlap"));
        assertEquals(Set.of("A", "B"), Set.copyOf((List<?>) second.get("overlapNutrientIds")));
        assertEquals(3.0, (double) second.get("score"), 1e-12);
        @SuppressWarnings("unchecked") Map<String, Object> breakdown = (Map<String, Object>) second.get("scoreBreakdown");
        assertEquals(2.0, (double) breakdown.get("overlapPenalty"), 1e-12);
        assertEquals(5.0, (double) breakdown.get("marginScore"), 1e-12);
        assertEquals(0.0, (double) breakdown.get("fixedScore"), 1e-12);
    }

    // ---------------------------------------------------------------- 재평가·중복 추천·동점

    @Test @DisplayName("재평가: 첫 선택으로 부족이 해소되면 같은 성분을 보완하던 후보의 점수가 다음 라운드에서 떨어진다")
    void reevaluatesAfterEachSelection() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("A", N.of(0d, 1d, 8d, 10d));
        nutrients.put("C", N.of(0d, 1d, null, null));
        Result result = rank(state(nutrients, null), List.of(
                candidate("a1", Map.of("A", 2d)),          // 보완 A, 여유 (8 − 2) ÷ 7
                candidate("a2", Map.of("A", 1.5)),         // 보완 A, 여유 (8 − 1.5) ÷ 7
                candidate("c", Map.of("C", 0.5))), Set.of());   // C는 0.5 < 1이라 보완 못 함
        // 1라운드: a2 = 10 + 5 × 6.5/7 = 14.64, a1 = 10 + 5 × 6/7 = 14.29, c = 5 → a2.
        // 2라운드: A가 1.5로 갱신되어 a1은 보완 0, 중복 1(A), 여유 (8 − 3.5) ÷ 7 → 2.21. c는 5 → c. 3라운드: a1.
        assertEquals(List.of("a2", "c", "a1"), ids(result));
        assertEquals(10 + 5 * (6.5 / 7), (double) result.items().get(0).get("score"), 1e-12);
        assertEquals(5.0, (double) result.items().get(1).get("score"), 1e-12);
        Map<String, Object> last = result.items().get(2);
        assertEquals(0L, last.get("fixedNutrients"));
        assertEquals(1L, last.get("overlap"));
        assertEquals(5 * (4.5 / 7) - 1, (double) last.get("score"), 1e-12);
        @SuppressWarnings("unchecked") Map<String, String> projected = (Map<String, String>) last.get("projectedStatuses");
        assertEquals("ADEQUATE", projected.get("A"));
        assertEquals("DEFICIENT", projected.get("C"));
    }

    @Test @DisplayName("같은 제품은 두 번 추천되지 않고, 동점이면 productId 오름차순(결정 F)")
    void noDuplicatesAndTieByProductId() {
        Map<String, N> nutrients = new LinkedHashMap<>();
        nutrients.put("A", N.of(5d, 1d, null, null));
        Result result = rank(state(nutrients, null), List.of(
                candidate("zeta", Map.of("A", 1d)), candidate("alpha", Map.of("A", 1d)), candidate("mid", Map.of("A", 1d))), Set.of());
        // 1라운드는 셋 모두 5점이라 alpha. 이후 A가 겹쳐 4점 동점 → mid, zeta.
        assertEquals(List.of("alpha", "mid", "zeta"), ids(result));
        assertEquals(3, new HashSet<>(ids(result)).size());
    }

    // ---------------------------------------------------------------- 카탈로그 경로

    @Test @DisplayName("카탈로그 경로: 급여 중 제품은 ALREADY_FED로 빠지고, 후보 값은 정규화 값(칼슘 500 mg → 0.5 g)과 체중별 용량을 쓴다")
    void catalogPath() {
        Profile adult = new Profile("검산", Species.DOG, 12, new Age(36, AgeUnit.MONTH), null, null, true);
        RecommendationRequest request = new RecommendationRequest(adult,
                List.of(new FeedingItem("feed-balanced-dog", 150, "G"), new FeedingItem("supp-zinc", 2, "TABLET")), List.of(), null, StandardProfile.NIAS_V3);
        Result result = recommender.recommend(request, nutrition.analyze(request.analysisRequest()), nias);
        assertEquals(NiasRecommendationService.ALREADY_FED, excluded(result, "supp-zinc").get("reasonCode"));
        List<Object> ids = ids(result);
        assertFalse(ids.contains("supp-zinc"));
        assertEquals(ids.size(), new HashSet<>(ids).size());
        for (Map<String, Object> item : result.items()) {
            @SuppressWarnings("unchecked") Map<String, Object> band = (Map<String, Object>) item.get("doseBand");
            assertEquals(2.0, item.get("dailyAmount"), item.toString());          // 12 kg → 10 kg 초과 구간
            assertEquals(1, band.get("bandIndex"));
            assertEquals(10d, band.get("aboveKg"));
            if (item.get("productId").equals("supp-multi")) assertTrue(((List<?>) item.get("overlapNutrientIds")).contains("ZINC"));
        }
        Candidate calcium = recommender.candidate(catalog.require("supp-calcium"), 12, Species.DOG);
        assertEquals(1.0, calcium.amounts().get("CALCIUM"), 1e-12);   // 0.5 g × 2회
        assertEquals(2.0, calcium.dose().dose());
    }

    @Test @DisplayName("수동 입력 영양제 성분도 중복에 센다")
    void manualSupplementKeysCountAsOverlap() throws Exception {
        Profile adult = new Profile("검산", Species.DOG, 8, new Age(36, AgeUnit.MONTH), null, null, true);
        ManualItem manual = new ManualItem("수동 비타민 E", ProductType.SUPPLEMENT, 1, "TABLET", 1, List.of(new ManualNutrient("VITAMIN_E", 1, "IU")));
        RecommendationRequest request = new RecommendationRequest(adult, List.of(new FeedingItem("feed-complete-unknown", 150, "G")), List.of(manual), null, StandardProfile.NIAS_V3);
        Result result = recommender.recommend(request, nutrition.analyze(request.analysisRequest()), nias);
        for (Map<String, Object> item : result.items())
            if (item.get("productId").equals("supp-omega")) assertTrue(((List<?>) item.get("overlapNutrientIds")).contains("VITAMIN_E"), item.toString());
        assertTrue(result.items().stream().anyMatch(item -> item.get("productId").equals("supp-omega"))
                || result.excluded().stream().anyMatch(entry -> entry.get("productId").equals("supp-omega")));
    }
}