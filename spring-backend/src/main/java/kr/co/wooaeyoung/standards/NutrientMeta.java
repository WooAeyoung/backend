package kr.co.wooaeyoung.standards;

import java.util.Map;

/** 분석 응답의 성분 식별자·NIAS 성분 번호(마스터에 없으면 null)·이름·응답 단위(G, MG, UG, IU). */
public record NutrientMeta(String id, Integer nutrientNo, String name, String unit) {
    private static final Map<String, String> RESPONSE_UNITS = Map.of("g", "G", "mg", "MG", "µg", "UG", "IU", "IU");

    /** 기준 단위(g, mg, µg, IU)를 기존 API 단위 코드로 바꾼다. */
    public static String responseUnit(String baseUnit) {
        String unit = RESPONSE_UNITS.get(baseUnit);
        if (unit == null) throw new IllegalStateException("허용하지 않는 단위: " + baseUnit);
        return unit;
    }
}
