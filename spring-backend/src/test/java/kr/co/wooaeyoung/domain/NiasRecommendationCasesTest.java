package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.domain.NiasRecommendationService.Candidate;
import kr.co.wooaeyoung.domain.NiasRecommendationService.Result;
import kr.co.wooaeyoung.domain.NiasRecommendationService.State;
import kr.co.wooaeyoung.standards.NiasV3Strategy;
import kr.co.wooaeyoung.standards.StandardProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 계산명세서 v3 11장 T14~T16(추천). 기대값은 docs/v3/계산_참조구현_v3.py를 저장소 밖에서 실행한 출력(반올림 전)이다.
 * 참조 구현에는 Ca:P 거르기가 없으므로 순서·점수 비교는 Ca:P 검사를 끈 상태로 하고(Issue #4 결정 A),
 * 운영 규칙(Ca:P 이탈 탈락)을 켠 결과는 별도 테스트로 둔다.
 * 허용 오차: 참조 출력과 1e-9, 명세 표시 자릿수(점수 소수 둘째 자리, 여유 셋째 자리) ±0.5단위.
 * Java는 사료 항목이 있어야 사료 몫을 추정하므로 성분 미표기 완전사료(feed-complete-unknown)를 함께 보낸다(NiasIntakeCasesTest와 같음).
 */
@SpringBootTest @ActiveProfiles("test")
class NiasRecommendationCasesTest {
    static final Profile T1 = new Profile("검산", Species.DOG, 10, new Age(36, AgeUnit.MONTH), null, null, null);
    static final NiasDose ONE = new NiasDose(1, NiasDose.DOSE_BY_WEIGHT, 0, null, 99d, false);
    /** 참조 구현 cands: 칼슘제(칼슘 0.9 g, 인 0.1 g), 비타민 D제(300 IU), 비타민 E제(20 IU), 모두 99 kg 이하 1회. */
    static final List<Candidate> CANDIDATES = List.of(
            new Candidate("ca", "칼슘제", "TABLET", ONE, Map.of("CALCIUM", 0.9, "PHOSPHORUS", 0.1), Set.of(), Set.of("CALCIUM", "PHOSPHORUS")),
            new Candidate("vd", "비타민 D제", "TABLET", ONE, Map.of("VITAMIN_D", 300d), Set.of(), Set.of("VITAMIN_D")),
            new Candidate("ve", "비타민 E제", "TABLET", ONE, Map.of("VITAMIN_E", 20d), Set.of(), Set.of("VITAMIN_E")));

    @Autowired NutritionService nutrition;
    @Autowired NiasRecommendationService recommender;
    @Autowired NiasV3Strategy nias;

    Map<String, Object> analysis(boolean completeFeed) {
        Profile profile = new Profile(T1.name(), T1.species(), T1.weightKg(), T1.age(), null, null, completeFeed);
        return nutrition.analyze(new AnalysisRequest(profile, List.of(new FeedingItem("feed-complete-unknown", 150, "G")), List.of(), StandardProfile.NIAS_V3));
    }

    Result rank(boolean completeFeed, boolean calciumPhosphorus, List<Candidate> candidates) {
        State state = NiasRecommendationService.state(analysis(completeFeed),
                calciumPhosphorus ? (calcium, phosphorus) -> nias.calciumPhosphorusStatus(T1, calcium, phosphorus) : null);
        return NiasRecommendationService.rank(state, candidates, Set.of(), recommender.weights().topN(), recommender.weights());
    }

    static void assertItem(Map<String, Object> item, String id, double score, long fixed, double margin, long overlap, List<String> fixedIds, String marginId) {
        assertEquals(id, item.get("productId"));
        assertEquals(score, (double) item.get("score"), 1e-9);
        assertEquals(fixed, item.get("fixedNutrients"));
        assertEquals(margin, (double) item.get("margin"), 1e-9);
        assertEquals(overlap, item.get("overlap"));
        assertEquals(fixedIds, item.get("fixedNutrientIds"));
        assertEquals(marginId, item.get("marginNutrientId"));
    }

    @Test void weightsComeFromParameters() {
        assertEquals(new NiasRecommendationService.Weights(10, 5, 1, 3), recommender.weights());
    }

    @Test @DisplayName("T14 T1 + 완전사료: 비타민 D제 탈락(377.3 ≥ 주의 371.1), [비타민 E제 5.0, 칼슘제 2.88(여유 0.58)], 부족 없음 안내")
    void t14() {
        Result result = rank(true, false, CANDIDATES);
        assertEquals(2, result.items().size());
        assertItem(result.items().get(0), "ve", 5.0, 0, 1.0, 0, List.of(), null);
        assertItem(result.items().get(1), "ca", 2.883700536813183, 0, 0.5767401073626366, 0, List.of(), "CALCIUM");
        assertEquals(2.88, (double) result.items().get(1).get("score"), 0.005);
        assertEquals(0.577, (double) result.items().get(1).get("margin"), 0.0005);
        assertFalse(result.hadDeficits());
        assertEquals(NiasRecommendationService.MESSAGE_NO_DEFICITS, result.message());
        assertEquals(1, result.excluded().size());
        Map<String, Object> vd = result.excluded().get(0);
        assertEquals("vd", vd.get("productId"));
        assertEquals(NiasRecommendationService.CAUTION, vd.get("reasonCode"));
        assertEquals(List.of(Map.of("code", "CAUTION", "nutrientIds", List.of("VITAMIN_D"))), vd.get("reasons"));
    }

    @Test @DisplayName("T15 T1 + 완전사료 해제: 비타민 E제 15.0 → 칼슘제 14.70(여유 0.94) → 비타민 D제 11.21(여유 0.242)")
    void t15() {
        Result result = rank(false, false, CANDIDATES);
        assertEquals(3, result.items().size());
        assertItem(result.items().get(0), "ve", 15.0, 1, 1.0, 0, List.of("VITAMIN_E"), null);
        assertItem(result.items().get(1), "ca", 14.701882354995002, 1, 0.9403764709990003, 0, List.of("CALCIUM"), "CALCIUM");
        assertItem(result.items().get(2), "vd", 11.210681119505486, 1, 0.242136223901097, 0, List.of("VITAMIN_D"), "VITAMIN_D");
        double[] display = {15.0, 14.70, 11.21}, margins = {1.0, 0.94, 0.242}, marginTolerance = {0.05, 0.005, 0.0005};
        for (int i = 0; i < 3; i++) {
            assertEquals(display[i], (double) result.items().get(i).get("score"), 0.005);
            assertEquals(margins[i], (double) result.items().get(i).get("margin"), marginTolerance[i]);
        }
        assertTrue(result.hadDeficits());
        assertEquals(NiasRecommendationService.MESSAGE_DEFICITS, result.message());
        assertTrue(result.excluded().isEmpty());
    }

    @Test @DisplayName("T16 후보 없음: 빈 목록과 '추가할 수 있는 제품이 없습니다'")
    void t16() {
        Result result = rank(true, false, List.of());
        assertTrue(result.items().isEmpty());
        assertTrue(result.excluded().isEmpty());
        assertEquals(NiasRecommendationService.MESSAGE_NONE, result.message());
    }

    @Test @DisplayName("T14 + Ca:P 거르기(운영 규칙): 칼슘제는 칼슘 1.67 / 인 0.72 = 2.33 > 2 → 탈락, 결과 [비타민 E제]")
    void t14WithCalciumPhosphorus() {
        Result result = rank(true, true, CANDIDATES);
        assertEquals(List.of("ve"), result.items().stream().map(item -> item.get("productId")).toList());
        Map<String, Object> ca = excluded(result, "ca");
        assertEquals(NiasRecommendationService.CALCIUM_PHOSPHORUS, ca.get("reasonCode"));
        assertEquals(List.of(Map.of("code", "CALCIUM_PHOSPHORUS", "nutrientIds", List.of("CALCIUM", "PHOSPHORUS"), "ratioStatus", "HIGH")), ca.get("reasons"));
        assertTrue(ca.get("reason").toString().contains("칼슘:인 비율 높음"));
        assertEquals(NiasRecommendationService.CAUTION, excluded(result, "vd").get("reasonCode"));
    }

    @Test @DisplayName("T15 + Ca:P 거르기(운영 규칙): 칼슘제는 0.9 / 0.1 = 9.0 → 탈락, 결과 [비타민 E제, 비타민 D제]")
    void t15WithCalciumPhosphorus() {
        Result result = rank(false, true, CANDIDATES);
        assertEquals(List.of("ve", "vd"), result.items().stream().map(item -> item.get("productId")).toList());
        assertEquals(11.210681119505486, (double) result.items().get(1).get("score"), 1e-9);
        assertEquals(NiasRecommendationService.CALCIUM_PHOSPHORUS, excluded(result, "ca").get("reasonCode"));
    }

    static Map<String, Object> excluded(Result result, String productId) {
        return result.excluded().stream().filter(entry -> productId.equals(entry.get("productId"))).findFirst()
                .orElseThrow(() -> new AssertionError(productId + "가 excluded에 없음: " + result.excluded()));
    }
}