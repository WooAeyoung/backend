package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
class JarvisServiceTest {
    private final JarvisService service = new JarvisService(null);

    @Test void explainsTheReferencedNutrientInsteadOfListingEveryDeficiency() {
        Map<String, Object> phosphorus = Map.of(
                "nutrientId", "PHOSPHORUS", "name", "인", "unit", "MG",
                "total", 120, "minimum", 300, "status", "DEFICIENT");

        Map<String, Object> result = service.answer(request("그래서 인이 왜 부족하냐니까", Map.of("nutrients", List.of(phosphorus))));

        assertEquals("NUTRIENT_EXPLANATION", result.get("intent"));
        assertTrue(result.get("answer").toString().contains("120mg"));
        assertTrue(result.get("answer").toString().contains("최소 기준 300mg"));
    }

    @Test void givesNextStepsAndAnswersThanks() {
        Map<String, Object> calcium = Map.of("nutrientId", "CALCIUM", "name", "칼슘", "status", "DEFICIENT");
        Map<String, Object> next = service.answer(request("자세히 앞으로 어떻게 하면 될까", Map.of("nutrients", List.of(calcium))));
        Map<String, Object> thanks = service.answer(request("감사", null));

        assertEquals("ANALYSIS_NEXT_STEPS", next.get("intent"));
        assertTrue(next.get("answer").toString().contains("하루 급여량"));
        assertEquals("COURTESY", thanks.get("intent"));
        assertTrue(thanks.get("answer").toString().contains("별말씀을요"));
    }

    private ApiModels.JarvisRequest request(String message, Map<String, Object> analysis) {
        return new ApiModels.JarvisRequest(message, analysis, null, List.of());
    }
}
