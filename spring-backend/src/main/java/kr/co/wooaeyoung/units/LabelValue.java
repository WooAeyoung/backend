package kr.co.wooaeyoung.units;

/**
 * 라벨 원문 한 줄. basis가 null이면 단위로 정한다(% → PERCENT, ppm → PER_KG, 그 밖 → PER_SERVING).
 * guarantee가 있으면 "이상/이하" 보증값이라 실제 함량으로 쓰지 않는다(처리기준 v3 7.1·7.3(3)).
 */
public record LabelValue(double amount, String unit, LabelBasis basis, NutrientForm form, Guarantee guarantee) {
    public enum Guarantee { MIN, MAX }

    public static LabelValue of(double amount, String unit) { return new LabelValue(amount, unit, null, null, null); }
}