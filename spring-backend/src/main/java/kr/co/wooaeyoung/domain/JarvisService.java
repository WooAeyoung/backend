package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class JarvisService {
    public Map<String, Object> answer(ApiModels.JarvisRequest request) {
        String phrase = request.message().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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
        if (phrase.matches(".*(분석|영양제).*")) return reply("ANALYSIS", "analysis", "영양제 분석 화면으로 이동할게요. 반려동물 정보와 급여 제품을 입력해 주세요.");
        if (phrase.matches(".*(고객센터|문의|도움말).*")) return reply("SUPPORT", "support", "고객센터로 이동할게요.");
        if (phrase.matches(".*(홈|처음|메인).*")) return reply("HOME", "home", "홈 화면으로 이동할게요.");
        return reply("HELP", null, "“영양제 분석”, “지난 기록 보여줘”, “오늘 분석 결과 요약해 줘”, “칼슘이 부족하면 어떻게 해?”라고 말씀해 보세요.");
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

    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of(); }
    private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
}
