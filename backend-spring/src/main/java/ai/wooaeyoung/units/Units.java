package ai.wooaeyoung.units;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 다중 단위 표준화 (F-011). 원본 backend/units.py 포트.
 *
 * 라벨 표기를 "1회 급여 기준량(g)당 mg" 으로 환산한다.
 */
@Component
public class Units {

    private static final Map<String, Double> MASS_TO_MG = new LinkedHashMap<>();
    static {
        MASS_TO_MG.put("mg", 1.0);
        MASS_TO_MG.put("㎎", 1.0);
        MASS_TO_MG.put("밀리그램", 1.0);
        MASS_TO_MG.put("g", 1000.0);
        MASS_TO_MG.put("그램", 1000.0);
        MASS_TO_MG.put("gram", 1000.0);
        MASS_TO_MG.put("kg", 1_000_000.0);
        MASS_TO_MG.put("㎏", 1_000_000.0);
        MASS_TO_MG.put("µg", 1e-3);
        MASS_TO_MG.put("㎍", 1e-3);
        MASS_TO_MG.put("ug", 1e-3);
        MASS_TO_MG.put("mcg", 1e-3);
        MASS_TO_MG.put("마이크로그램", 1e-3);
    }

    private static final Map<String, Double> IU_TO_MG = Map.of(
            "비타민D", 2.5e-5,
            "비타민A", 3e-4,
            "비타민E", 6.7e-1
    );

    private static final Pattern PER_SERVING = Pattern.compile(
            "(mg|㎎|µg|㎍|mcg|g)\\s*/\\s*(정|캡슐|알|포|스쿱|회분|tablet|capsule|serving)");

    public double toMgPerServing(double value, String unit, String nutrient, UnitContext ctxIn) {
        UnitContext ctx = ctxIn == null ? UnitContext.DEFAULT : ctxIn;
        String u = unit.strip().toLowerCase();
        while (u.endsWith(".")) {
            u = u.substring(0, u.length() - 1);
        }
        u = u.replace("퍼센트", "%").replace("prozent", "%");

        if (MASS_TO_MG.containsKey(u)) {
            return value * MASS_TO_MG.get(u);
        }
        if (u.equals("%") || u.equals("백분율") || u.equals("percent")) {
            return value / 100.0 * ctx.servingBasisG() * 1000.0;
        }
        if (u.equals("mg/kg") || u.equals("ppm") || u.equals("㎎/㎏")) {
            return value * (ctx.servingBasisG() / 1000.0);
        }
        if (u.equals("g/kg") || u.equals("‰") || u.equals("permille")) {
            return value * (ctx.servingBasisG() / 1000.0) * 1000.0;
        }
        if (u.equals("iu") || u.equals("i.u") || u.equals("국제단위")) {
            Double factor = IU_TO_MG.get(nutrient);
            if (factor == null) {
                throw new UnitError(nutrient + " 은(는) IU→mg 환산 계수가 없습니다.");
            }
            return value * factor;
        }
        if (u.equals("iu/kg") || u.equals("국제단위/kg")) {
            Double factor = IU_TO_MG.get(nutrient);
            if (factor == null) {
                throw new UnitError(nutrient + " 은(는) IU/kg→mg 환산 계수가 없습니다.");
            }
            return value * factor * (ctx.servingBasisG() / 1000.0);
        }
        Matcher m = PER_SERVING.matcher(u);
        if (m.matches()) {
            double base = MASS_TO_MG.get(m.group(1));
            return value * base * ctx.servingsPerPack();
        }
        throw new UnitError("알 수 없는 단위: '" + unit + "'");
    }

    public java.util.List<String> supportedUnits() {
        TreeSet<String> all = new TreeSet<>(MASS_TO_MG.keySet());
        all.addAll(java.util.List.of("%", "mg/kg", "ppm", "IU", "IU/kg", "mg/정", "g/kg"));
        return new java.util.ArrayList<>(all);
    }
}
