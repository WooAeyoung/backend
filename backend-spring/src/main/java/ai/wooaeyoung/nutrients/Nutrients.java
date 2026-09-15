package ai.wooaeyoung.nutrients;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 성분명 정규화 (F-032). 원본 backend/nutrients.py 포트.
 *
 * 라벨·API 로 들어오는 다양한 표기(Ca / 칼슘 / calcium, Vit D3 / 비타민디 …)를
 * 기준표와 맞는 표준 한글명 하나로 접는다.
 */
@Component
public class Nutrients {

    public static final Map<String, List<String>> CANONICAL = new LinkedHashMap<>();
    static {
        CANONICAL.put("칼슘", List.of("칼슘", "칼 슘", "calcium", "ca"));
        CANONICAL.put("인", List.of("인산", "인(p)", "인", "phosphorus", "phosphorous", "p"));
        CANONICAL.put("칼슘:인 비율", List.of("칼슘:인", "ca:p", "칼슘 인 비율", "ca/p"));
        CANONICAL.put("비타민D", List.of(
                "비타민d3", "비타민 d3", "비타민-d3", "비타민d", "비타민 d", "비타민디",
                "vitamin d3", "vitamin d", "vit d3", "vit d", "vit.d", "cholecalciferol", "콜레칼시페롤"));
        CANONICAL.put("비타민A", List.of("비타민a", "비타민 a", "vitamin a", "vit a", "retinol", "레티놀"));
        CANONICAL.put("비타민E", List.of("비타민e", "비타민 e", "vitamin e", "vit e", "tocopherol", "토코페롤"));
        CANONICAL.put("비타민C", List.of("비타민c", "비타민 c", "vitamin c", "ascorbic acid", "아스코르브산"));
        CANONICAL.put("아연", List.of("아연", "zinc", "zn"));
        CANONICAL.put("철", List.of("철분", "철", "iron", "fe"));
        CANONICAL.put("구리", List.of("구리", "동", "copper", "cu"));
        CANONICAL.put("망간", List.of("망간", "manganese", "mn"));
        CANONICAL.put("요오드", List.of("요오드", "아이오딘", "iodine", "i2", "iodide"));
        CANONICAL.put("셀레늄", List.of("셀레늄", "셀렌", "selenium", "se"));
        CANONICAL.put("마그네슘", List.of("마그네슘", "magnesium", "mg"));
        CANONICAL.put("칼륨", List.of("칼륨", "포타슘", "potassium", "k"));
        CANONICAL.put("나트륨", List.of("나트륨", "소듐", "sodium", "na", "소금", "염분"));
        CANONICAL.put("조단백", List.of("조단백", "조단백질", "단백질", "crude protein", "protein"));
        CANONICAL.put("조지방", List.of("조지방", "지방", "crude fat", "fat", "ether extract"));
        CANONICAL.put("조섬유", List.of("조섬유", "섬유질", "crude fiber", "fibre", "fiber"));
        CANONICAL.put("오메가3", List.of("오메가3", "오메가-3", "omega 3", "omega-3", "n-3", "epa+dha", "epa", "dha"));
        CANONICAL.put("오메가6", List.of("오메가6", "오메가-6", "omega 6", "omega-6", "n-6", "리놀레산", "linoleic"));
        CANONICAL.put("타우린", List.of("타우린", "taurine"));
        CANONICAL.put("글루코사민", List.of("글루코사민", "glucosamine"));
        CANONICAL.put("콘드로이틴", List.of("콘드로이틴", "chondroitin"));
    }

    private static final Map<String, String> ALIAS_TO_CANON = new LinkedHashMap<>();
    static {
        CANONICAL.forEach((canon, aliases) -> {
            ALIAS_TO_CANON.put(canon.toLowerCase(), canon);
            for (String a : aliases) {
                ALIAS_TO_CANON.put(a.toLowerCase(), canon);
            }
        });
    }

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern PAREN = Pattern.compile("[(（].*?[)）]");
    private static final Pattern AS_TAIL = Pattern.compile("\\bas\\b.*$");

    private String norm(String text) {
        if (text == null) return "";
        return WS.matcher(text.strip().toLowerCase()).replaceAll(" ");
    }

    public String normalizeNutrientName(String name) {
        String key = norm(name);
        if (ALIAS_TO_CANON.containsKey(key)) {
            return ALIAS_TO_CANON.get(key);
        }
        String stripped = PAREN.matcher(key).replaceAll("");
        stripped = AS_TAIL.matcher(stripped).replaceAll("").strip();
        if (ALIAS_TO_CANON.containsKey(stripped)) {
            return ALIAS_TO_CANON.get(stripped);
        }
        for (Map.Entry<String, String> e : ALIAS_TO_CANON.entrySet()) {
            String alias = e.getKey();
            if (!alias.isEmpty() && (alias.equals(stripped) || (alias.length() >= 2 && key.contains(alias)))) {
                return e.getValue();
            }
        }
        return name == null ? "" : name.strip();
    }

    public boolean isKnown(String name) {
        return ALIAS_TO_CANON.containsKey(norm(name));
    }
}
