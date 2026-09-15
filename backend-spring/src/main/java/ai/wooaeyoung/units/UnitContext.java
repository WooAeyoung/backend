package ai.wooaeyoung.units;

/** 라벨 기준량(g) · 1회분 개수. 원본 units.py의 UnitContext. */
public record UnitContext(double servingBasisG, double servingsPerPack) {
    public static final UnitContext DEFAULT = new UnitContext(100.0, 1.0);
}
