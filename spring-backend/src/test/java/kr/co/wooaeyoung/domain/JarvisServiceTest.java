package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
class JarvisServiceTest {
    private final JarvisAiService ai = new JarvisAiService() {
        @Override public Optional<String> complete(String message, Map<String, Object> analysis, String provider, List<ApiModels.JarvisMessage> history) {
            return Optional.empty();
        }
        @Override public boolean hasConfiguredProvider() { return false; }
        @Override public String lastFailureCategory() { return "MISSING_CREDENTIALS"; }
    };
    private final JarvisService service = new JarvisService(ai);

    @Test void buildsOpenJarvisCompatibleChatEndpoint() {
        assertEquals("http://localhost:8000/v1/chat/completions", JarvisAiService.openJarvisChatUrl("http://localhost:8000"));
        assertEquals("https://jarvis.example/v1/chat/completions", JarvisAiService.openJarvisChatUrl("https://jarvis.example/v1/"));
        assertThrows(IllegalArgumentException.class, () -> JarvisAiService.openJarvisChatUrl("file:///tmp/jarvis"));
    }

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

    @Test void marksUnavailableAiSoTheFrontendCanRetryWithoutRetyping() {
        Map<String, Object> result = service.answer(request("이건 왜 그런 거야?", null));

        assertEquals("AI_NOT_CONFIGURED", result.get("intent"));
        assertTrue(result.get("answer").toString().contains("연결 설정이 아직 완료되지 않았어요"));
    }

    @Test void answersCommonNutrientQuestionsWithoutExternalAi() {
        Map<String, Object> result = service.answer(request("인 궁금해", null));

        assertEquals("NUTRIENT_INFORMATION", result.get("intent"));
        assertTrue(result.get("answer").toString().contains("뼈와 치아"));
        assertTrue(result.get("answer").toString().contains("칼슘과의 비율"));
    }

    @Test void routesHistoricalAnalysisQuestionsToSavedRecords() {
        Map<String, Object> result = service.answer(request("과거에 fg 분석했던거 설명", null));

        assertEquals("RECORDS", result.get("intent"));
        assertEquals("records", result.get("page"));
        assertTrue(result.get("answer").toString().contains("저장 기록 화면"));
    }

    @Test void routesResultExplanationToRecordsWhenThereIsNoCurrentAnalysis() {
        Map<String, Object> result = service.answer(request("분석결과 설명", null));

        assertEquals("RECORDS", result.get("intent"));
        assertEquals("records", result.get("page"));
        assertTrue(result.get("answer").toString().contains("저장 기록 화면"));
    }

    @Test void usesExternalAiForAnalysisConversationUnlessNavigationIsExplicit() {
        JarvisAiService answeringAi = new JarvisAiService() {
            @Override public Optional<String> complete(String message, Map<String, Object> analysis, String provider, List<ApiModels.JarvisMessage> history) {
                return Optional.of("외부 AI 답변");
            }
        };
        JarvisService externalService = new JarvisService(answeringAi);

        Map<String, Object> conversation = externalService.answer(request("영양 분석이 궁금해", null));
        Map<String, Object> navigation = externalService.answer(request("분석 화면 열어줘", null));

        assertEquals("AI_ASSISTANT", conversation.get("intent"));
        assertNull(conversation.get("page"));
        assertEquals("ANALYSIS", navigation.get("intent"));
        assertEquals("analysis", navigation.get("page"));
    }

    @Test void fallsBackToAUsefulLocalAnswerWhenProvidersAreUnavailable() {
        JarvisAiService unavailableAi = new JarvisAiService() {
            @Override public Optional<String> complete(String message, Map<String, Object> analysis, String provider, List<ApiModels.JarvisMessage> history) {
                return Optional.empty();
            }
            @Override public boolean hasConfiguredProvider() { return true; }
            @Override public String lastFailureCategory() { return "PROVIDER_ERROR"; }
        };

        Map<String, Object> result = new JarvisService(unavailableAi).answer(request("영양 분석에서 주의할 점", null));

        assertEquals("AI_ASSISTANT", result.get("intent"));
        assertNull(result.get("page"));
        assertTrue(result.get("answer").toString().contains("사료·영양제"));
    }
}
