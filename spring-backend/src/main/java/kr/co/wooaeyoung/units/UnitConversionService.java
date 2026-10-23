package kr.co.wooaeyoung.units;

import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.standards.NutrientMaster;
import kr.co.wooaeyoung.units.Conversion.Status;
import kr.co.wooaeyoung.units.UnitConversionFactors.Alias;
import kr.co.wooaeyoung.units.UnitConversionFactors.BasisEntry;
import kr.co.wooaeyoung.units.UnitConversionFactors.IuFactor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 라벨 값 → 기준 단위(nutrients-master.json baseUnit) 환산(BE-V3-010, 처리기준 v3 7.3).
 * 정보가 부족하면 값을 만들지 않고 INSUFFICIENT_DATA를 돌려준다. 환산값은 반올림하지 않는다.
 * 입력 자체가 모순이면(음수, 성분과 맞지 않는 원료, 단위와 맞지 않는 표기 형태) IllegalArgumentException.
 */
@Service
public class UnitConversionService {
    private static final String IU = "IU", PERCENT = "%", PPM = "ppm";

    /** 환산 문맥. 종은 베타카로틴, 수분은 건물 기준, 대사에너지(kcal/kg)는 1000 kcal당 표기에만 쓴다. */
    public record Context(Species species, Double moisturePercent, Double energyKcalPerKg) {
        public static final Context NONE = new Context(null, null, null);
    }

    private final UnitConversionFactors factors;
    private final NutrientMaster master;

    public UnitConversionService(UnitConversionFactors factors, NutrientMaster master) {
        this.factors = factors;
        this.master = master;
    }

    public UnitConversionFactors factors() { return factors; }

    /** nutrient 성분의 기준 단위로 환산한다. EPA·DHA는 "EPA_DHA"로 각각 환산한 뒤 {@link #combineEpaDha}로 합친다. */
    public Conversion convert(String nutrient, LabelValue label, Context context) {
        String baseUnit = master.find(nutrient).orElseThrow(() -> new IllegalArgumentException("알 수 없는 성분: " + nutrient)).baseUnit();
        if (Double.isNaN(label.amount()) || Double.isInfinite(label.amount()) || label.amount() < 0)
            throw new IllegalArgumentException("함량은 0 이상의 유한한 값이어야 합니다: " + nutrient + "=" + label.amount());
        if (label.form() != null && !label.form().nutrient().equals(nutrient))
            throw new IllegalArgumentException("원료 형태 " + label.form() + "는 " + label.form().nutrient() + " 성분용입니다: " + nutrient);
        Context ctx = context == null ? Context.NONE : context;
        String form = label.form() == null ? null : label.form().name();
        Alias alias = factors.alias(label.unit()).orElse(null);
        if (alias == null)
            return record(Status.UNIT_CONVERSION_UNSUPPORTED, null, false, label, null, form, baseUnit, null, null, "환산표에 없는 단위: " + label.unit());
        String unit = alias.unit();
        LabelBasis basis = basisOf(nutrient, label.basis(), unit);
        BasisEntry entry = factors.basis(basis);
        if (label.guarantee() != null)
            return record(Status.NO_VALUE, null, entry.perGram(), label, basis, form, baseUnit, null, entry.source(),
                    "보증값(" + (label.guarantee() == LabelValue.Guarantee.MIN ? "이상" : "이하") + ")은 실제 함량이 아니라 정보 없음으로 처리");

        List<String> steps = new ArrayList<>();
        List<String> sources = new ArrayList<>(List.of(entry.source()));
        double value = label.amount();
        // 1) 표기 형태 → 1 g당(또는 1회 제공량당)
        if ("ENERGY".equals(entry.requires())) {
            Double energy = ctx.energyKcalPerKg();
            if (energy == null || energy <= 0)
                return record(Status.INSUFFICIENT_DATA, null, true, label, basis, form, baseUnit, null, entry.source(), "1000 kcal당 표기는 사료 대사에너지(kcal/kg)가 있어야 환산할 수 있음");
            value = value * energy / 1000 / entry.divisor();
            steps.add(plain(label.amount()) + " " + unit + "/1000 kcal × (" + plain(energy) + " kcal/kg ÷ 1000) ÷ " + plain(entry.divisor()) + " = " + plain(value) + " " + unit + "/g");
        } else if ("MOISTURE".equals(entry.requires())) {
            Double moisture = ctx.moisturePercent();
            if (moisture == null)
                return record(Status.INSUFFICIENT_DATA, null, true, label, basis, form, baseUnit, null, entry.source(), "건물 기준 표기는 수분(%)이 있어야 환산할 수 있음");
            if (moisture < 0 || moisture >= 100) throw new IllegalArgumentException("수분(%)은 0 이상 100 미만이어야 합니다: " + moisture);
            value = value * (1 - moisture / 100) / entry.divisor();
            steps.add(plain(label.amount()) + " % DM × (1 − " + plain(moisture) + " % ÷ 100) ÷ " + plain(entry.divisor()) + " = " + plain(value) + " g/g");
        } else if (entry.divisor() != 1) {
            value = value / entry.divisor();
            steps.add(plain(label.amount()) + " " + unit + " ÷ " + plain(entry.divisor()) + " = " + plain(value) + " " + labelUnit(unit) + "/g");
        }
        String massUnit = labelUnit(unit);

        // 2) 단위 → 기준 단위
        String note = null;
        if (baseUnit.equals(IU)) {
            if (massUnit.equals(IU)) {
                if (steps.isEmpty()) steps.add(plain(value) + " IU (그대로)");
            } else {
                if (label.form() == null)
                    return record(Status.INSUFFICIENT_DATA, null, entry.perGram(), label, basis, null, baseUnit, join(steps), String.join("; ", sources),
                            nutrient + "를 " + massUnit + "에서 IU로 바꾸려면 원료 형태가 필요함(원료 미상이면 간주 값을 명시적으로 골라야 함)");
                Optional<IuFactor> found = factors.iuFactor(label.form(), ctx.species());
                if (found.isEmpty())
                    return record(Status.INSUFFICIENT_DATA, null, entry.perGram(), label, basis, form, baseUnit, join(steps), String.join("; ", sources),
                            label.form() + " 환산은 종(개·고양이)에 따라 달라 종 정보가 필요함");
                IuFactor factor = found.get();
                sources.add(factor.source());
                if (factor.excluded())
                    return record(Status.EXCLUDED, null, entry.perGram(), label, basis, form, baseUnit, join(steps), String.join("; ", sources), factor.note());
                double inFactorUnit = scale(value, massUnit, factor.unit());
                if (!massUnit.equals(factor.unit())) steps.add(plain(value) + " " + massUnit + " = " + plain(inFactorUnit) + " " + factor.unit());
                double iu = inFactorUnit * factor.iu() / factor.amount();
                steps.add(plain(inFactorUnit) + " " + factor.unit() + " × " + plain(factor.iu()) + " IU ÷ " + plain(factor.amount()) + " " + factor.unit() + " = " + plain(iu) + " IU");
                value = iu;
                note = factor.note();
            }
        } else {
            if (factors.massUnit(massUnit).isEmpty())
                return record(Status.UNIT_CONVERSION_UNSUPPORTED, null, entry.perGram(), label, basis, form, baseUnit, join(steps), String.join("; ", sources),
                        massUnit + "는 질량 기준 단위 " + baseUnit + "로 환산할 수 없음");
            double scaled = scale(value, massUnit, baseUnit);
            if (!massUnit.equals(baseUnit)) {
                sources.add(factors.massUnit(baseUnit).orElseThrow().source());
                steps.add(plain(value) + " " + massUnit + " = " + plain(scaled) + " " + baseUnit);
            } else if (steps.isEmpty()) steps.add(plain(value) + " " + baseUnit + " (그대로)");
            value = scaled;
        }
        return record(Status.CONVERTED, value, entry.perGram(), label, basis, form, baseUnit, join(steps), String.join("; ", sources), note);
    }

    /**
     * EPA와 DHA 환산 기록을 EPA+DHA 하나로 합친다(처리기준 v3 7.3(5)). 둘 다 없고 총 오메가3만 있으면 NO_VALUE.
     * 하나만 있으면 표기된 값만 쓰고 note에 남긴다. 표기가 아예 없으면 empty.
     */
    public Optional<Conversion> combineEpaDha(Conversion epa, Conversion dha, Double totalOmega3, String totalOmega3Unit) {
        String target = factors.omega3().target(), baseUnit = master.find(target).orElseThrow().baseUnit();
        String source = factors.omega3().source();
        if (epa == null && dha == null) {
            if (totalOmega3 == null) return Optional.empty();
            return Optional.of(new Conversion(Status.NO_VALUE, null, false, totalOmega3, totalOmega3Unit, null, null, baseUnit, null, source,
                    "총 오메가3만 표기되어 EPA+DHA로 쓰지 않음(알파-리놀렌산 혼재 가능)"));
        }
        for (Conversion part : new Conversion[]{epa, dha})
            if (part != null && !part.converted()) return Optional.of(part);
        if (epa == null || dha == null) {
            Conversion only = epa == null ? dha : epa;
            String name = epa == null ? "DHA" : "EPA", missing = epa == null ? "EPA" : "DHA";
            return Optional.of(new Conversion(Status.CONVERTED, only.value(), only.perGram(), only.originalValue(), only.originalUnit(), only.basis(), null,
                    baseUnit, name + " " + only.formula(), source, missing + " 미표기: 표기된 " + name + "만 EPA+DHA에 넣음"));
        }
        if (epa.perGram() != dha.perGram()) throw new IllegalArgumentException("EPA와 DHA의 표기 기준(1 g당/1회 제공량당)이 다릅니다.");
        double sum = epa.value() + dha.value();
        // 원문 단위가 같을 때만 원문 값을 더해 남긴다. 다르면 각 원문은 formula에 있다.
        boolean sameUnit = epa.originalUnit().equals(dha.originalUnit()) && java.util.Objects.equals(epa.basis(), dha.basis());
        return Optional.of(new Conversion(Status.CONVERTED, sum, epa.perGram(), sameUnit ? epa.originalValue() + dha.originalValue() : sum,
                sameUnit ? epa.originalUnit() : baseUnit, sameUnit ? epa.basis() : null, null,
                baseUnit, "EPA " + epa.formula() + "; DHA " + dha.formula() + "; EPA " + plain(epa.value()) + " + DHA " + plain(dha.value()) + " = " + plain(sum) + " " + baseUnit,
                source, "EPA·DHA 합산"));
    }

    private LabelBasis basisOf(String nutrient, LabelBasis requested, String unit) {
        if (unit.equals(PERCENT)) {
            if (requested == null) return LabelBasis.PERCENT;
            if (requested != LabelBasis.PERCENT && requested != LabelBasis.DRY_MATTER_PERCENT)
                throw new IllegalArgumentException(nutrient + ": % 단위는 PERCENT 또는 DRY_MATTER_PERCENT 표기에만 씁니다(" + requested + ")");
            return requested;
        }
        if (requested == LabelBasis.PERCENT || requested == LabelBasis.DRY_MATTER_PERCENT)
            throw new IllegalArgumentException(nutrient + ": " + requested + " 표기는 % 단위여야 합니다(" + unit + ")");
        if (unit.equals(PPM)) {
            if (requested != null && requested != LabelBasis.PER_KG) throw new IllegalArgumentException(nutrient + ": ppm은 PER_KG(mg/kg) 표기입니다(" + requested + ")");
            return LabelBasis.PER_KG;
        }
        return requested == null ? LabelBasis.PER_SERVING : requested;
    }

    /** % → g, ppm → mg(처리기준 v3 7.3(3)). */
    private static String labelUnit(String unit) {
        return switch (unit) { case PERCENT -> "g"; case PPM -> "mg"; default -> unit; };
    }

    /** 10의 거듭제곱으로 나누거나 곱해 접두 단위를 바꾼다(0.001 같은 부정확한 계수를 곱하지 않는다). */
    private double scale(double value, String from, String to) {
        int diff = factors.massUnit(from).orElseThrow().gramsExponent() - factors.massUnit(to).orElseThrow().gramsExponent();
        if (diff == 0) return value;
        double power = Math.pow(10, Math.abs(diff));
        return diff > 0 ? value * power : value / power;
    }

    private static Conversion record(Status status, Double value, boolean perGram, LabelValue label, LabelBasis basis, String form,
                                     String baseUnit, String formula, String source, String note) {
        return new Conversion(status, value, perGram, label.amount(), label.unit(), basis == null ? null : basis.name(), form, baseUnit, formula, source, note);
    }

    private static String join(List<String> steps) { return steps.isEmpty() ? null : String.join("; ", steps); }

    /** 환산식 표시용. 반올림하지 않고 double의 최단 표현을 쓴다. */
    static String plain(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
