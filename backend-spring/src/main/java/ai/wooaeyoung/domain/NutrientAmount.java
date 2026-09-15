package ai.wooaeyoung.domain;

/** 제품 1회 급여 기준(serving_basis_g)당 성분 함량. 데모 카탈로그({@link Product})와
 * 사용자 저장 제품({@link SavedProduct}) 양쪽에서 공유한다. */
public record NutrientAmount(String nutrient, double amountMg, boolean labelComplete) {}
