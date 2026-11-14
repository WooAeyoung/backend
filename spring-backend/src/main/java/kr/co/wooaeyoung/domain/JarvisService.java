package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class JarvisService {
    private final JarvisAiService ai;
    public JarvisService(JarvisAiService ai) { this.ai = ai; }
    public Map<String, Object> answer(ApiModels.JarvisRequest request) {
        String phrase = request.message().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (phrase.contains("부족한성분만보여줘")) {
            return reply("DEFICIENT_NUTRIENTS", "analysis", deficientAnswer(request.analysis()));
        }
        if (phrase.matches(".*(오늘|최근).*(분석|결과).*(요약|설명).*|.*(요약|설명).*(분석|결과).*")) {
            return reply("ANALYSIS_SUMMARY", "analysis", describeAnalysis(request.analysis()));
        }
        if (phrase.matches(".*칼슘.*(부족|낮|어떻게|도움).*")) {
            return reply("CALCIUM_GUIDANCE", "analysis", calciumGuidance(request.analysis()));
        }
        if (phrase.matches(".*추천.*(영양제|제품|찾).*")) {
            String answer = request.analysis() == null
                    ? "추천은 반려동물 정보와 현재 급여 제품을 분석한 뒤 제공해요. 먼저 분석 화면에서 정보를 입력해 주세요."
                    : "현재 분석 결과 아래의 ‘추천 영양제’에서 추천 목록을 확인할 수 있어요.";
            return reply("RECOMMENDATION", "analysis", answer);
        }
        if (phrase.matches(".*(지난|이전|반려동물).*(기록|결과).*|.*기록(을|보여|조회).*")) {
            return reply("RECORDS", "records", "반려동물 기록 화면을 열었어요. 저장한 분석 결과와 프로필을 확인할 수 있어요.");
        }
        if (isAnalysisNavigation(phrase)) return reply("ANALYSIS", "analysis", "영양제 분석 화면으로 이동할게요. 반려동물 정보와 급여 제품을 입력해 주세요.");
        if (phrase.matches(".*(고객센터|문의|도움말).*")) return reply("SUPPORT", "support", "고객센터로 이동할게요.");
        if (phrase.matches(".*(홈|처음|메인).*")) return reply("HOME", "home", "홈 화면으로 이동할게요.");
        String localAnswer = answerFromAnalysis(request.message(), request.analysis());
        Optional<String> aiAnswer = ai.complete(request.message(), request.analysis(), request.provider(), request.history());
        return reply("AI_ASSISTANT", null, aiAnswer.filter(answer -> !answer.isBlank()).orElse(localAnswer));
    }

    private boolean isAnalysisNavigation(String phrase) {
        return phrase.matches("(분석|영양제분석)")
                || phrase.matches(".*(분석|영양제).*(화면|시작|열어|이동|보여).*");
    }

    private Map<String, Object> reply(String intent, String page, String answer) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent); result.put("page", page); result.put("answer", answer);
        return result;
    }

    private String describeAnalysis(Map<String, Object> analysis) {
        if (analysis == null) return "아직 완료된 분석 결과가 없어요. 반려동물 정보와 급여 제품을 입력한 뒤 ‘맞춤 분석 시작하기’를 눌러 주세요.";
        Map<String, Object> summary = map(analysis.get("summary"));
        int adequate = number(summary.get("adequate")), deficient = number(summary.get("deficient"));
        int caution = number(summary.get("caution")), excess = number(summary.get("excess"));
        List<String> concerns = new ArrayList<>();
        Object nutrients = analysis.get("nutrients");
        if (nutrients instanceof List<?> rows) for (Object row : rows) {
            Map<String, Object> nutrient = map(row); String status = String.valueOf(nutrient.get("status"));
            if (Set.of("DEFICIENT", "CAUTION", "EXCESS").contains(status)) concerns.add(nutrient.get("name") + " " + ("DEFICIENT".equals(status) ? "부족" : "EXCESS".equals(status) ? "과다" : "주의"));
        }
        String issue = concerns.isEmpty() ? "현재 확인된 주의 항목은 없어요." : "확인할 항목은 " + String.join(", ", concerns.subList(0, Math.min(3, concerns.size()))) + "입니다.";
        return "오늘 결과는 적정 " + adequate + "개, 부족 " + deficient + "개, 주의 " + caution + "개, 과다 " + excess + "개예요. " + issue + " 이 결과는 진단이나 처방이 아니며, 증상이 있으면 수의사와 상담해 주세요.";
    }

    private String calciumGuidance(Map<String, Object> analysis) {
        if (analysis != null && analysis.get("nutrients") instanceof List<?> rows) for (Object row : rows) {
            Map<String, Object> nutrient = map(row);
            if ("CALCIUM".equals(nutrient.get("nutrientId"))) {
                String status = String.valueOf(nutrient.get("status"));
                String label = "DEFICIENT".equals(status) ? "부족" : "EXCESS".equals(status) ? "과다" : "CAUTION".equals(status) ? "주의" : "적정";
                return "최근 분석에서 칼슘은 " + label + "으로 표시됐어요. 임의로 칼슘제를 더하기보다 현재 사료·영양제와 급여량을 확인해 다시 분석해 주세요. 증상이 있으면 수의사와 상담해 주세요.";
            }
        }
        return "칼슘 부족 여부는 사료와 영양제를 합친 하루 섭취량, 나이·체중, 칼슘:인 비율을 함께 봐야 해요. 임의로 칼슘제를 추가하지 말고 급여 제품을 등록해 분석한 뒤 결과를 확인해 주세요. 증상이 있으면 수의사와 상담해 주세요.";
    }

    private String answerFromAnalysis(String message, Map<String, Object> analysis) {
        String normalized = message.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(안녕|하이|hi|hello).*")) return "안녕하세요! 궁금한 점을 편하게 물어보세요. 영양 정보나 현재 분석 결과를 함께 살펴드릴게요.";
        if (normalized.matches(".*(왜|이유).*(수의사|병원).*") || normalized.matches(".*(수의사|병원).*(왜|이유).*")) {
            return "영양 분석은 입력한 사료와 영양제의 수치를 비교하는 참고 자료라서, 실제 증상·질환·약물·검사 결과까지 판단할 수 없어요. 이상 증상이 있거나 부족·과다 상태가 계속되면 안전을 위해 수의사와 상담하라는 뜻이에요.";
        }
        if (analysis == null) return "지금 질문은 이해했지만 AI 답변을 잠시 불러오지 못했어요. 다시 한 번 물어보시면 이어서 답해 드릴게요.";
        if (normalized.contains("부족")) return deficientAnswer(analysis);
        if (normalized.contains("칼슘") || normalized.contains("비율")) return calciumGuidance(analysis);
        return "현재 분석 결과는 불러와져 있어요. 궁금한 성분이나 결과의 이유를 조금 더 구체적으로 물어보시면 해당 내용을 설명해 드릴게요.";
    }

    private String deficientAnswer(Map<String, Object> analysis) {
        if (analysis == null) return "아직 분석 결과가 없어요. 영양제 분석을 완료하면 실제 부족 성분을 확인해 드릴게요.";
        List<String> deficient = new ArrayList<>();
        if (analysis.get("nutrients") instanceof List<?> rows) for (Object row : rows) {
            Map<String, Object> nutrient = map(row);
            if ("DEFICIENT".equals(String.valueOf(nutrient.get("status")))) {
                Object name = nutrient.get("name");
                if (name != null && !String.valueOf(name).isBlank()) deficient.add(String.valueOf(name));
            }
        }
        return deficient.isEmpty()
                ? "현재 분석 결과에서 부족으로 표시된 성분은 없어요."
                : "현재 분석 결과에서 부족한 성분은 " + String.join(", ", deficient) + "이에요.";
    }

    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of(); }
    private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
}
