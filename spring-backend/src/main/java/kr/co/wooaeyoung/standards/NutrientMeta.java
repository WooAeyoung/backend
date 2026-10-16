package kr.co.wooaeyoung.standards;

/** 분석 응답의 성분 식별자·이름·응답 단위(MG, UG 등). */
public record NutrientMeta(String id, String name, String unit) {}