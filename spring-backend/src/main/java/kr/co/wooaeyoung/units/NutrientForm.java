package kr.co.wooaeyoung.units;

/**
 * 비타민 A·D·E의 라벨 원료 형태(처리기준 v3 7.3(4), 영양표준 표 7-5). 계수는 conversion-factors.json의 iuFactors에 있다.
 * UNKNOWN_ASSUME_* 는 원료가 표기되지 않았을 때 사용자가 명시적으로 고르는 간주 값이다. 고르지 않으면 INSUFFICIENT_DATA.
 */
public enum NutrientForm {
    RETINOL("VITAMIN_A"),
    RETINYL_ACETATE("VITAMIN_A"),
    RETINYL_PROPIONATE("VITAMIN_A"),
    RETINYL_PALMITATE("VITAMIN_A"),
    RETINOL_EQUIVALENT("VITAMIN_A"),
    BETA_CAROTENE("VITAMIN_A"),
    UNKNOWN_ASSUME_RETINOL_EQUIVALENT("VITAMIN_A"),
    CHOLECALCIFEROL("VITAMIN_D"),
    UNKNOWN_ASSUME_CHOLECALCIFEROL("VITAMIN_D"),
    DL_ALPHA_TOCOPHERYL_ACETATE("VITAMIN_E"),
    D_ALPHA_TOCOPHEROL("VITAMIN_E"),
    D_ALPHA_TOCOPHERYL_ACETATE("VITAMIN_E"),
    DL_ALPHA_TOCOPHEROL("VITAMIN_E"),
    UNKNOWN_ASSUME_DL_ALPHA_TOCOPHERYL_ACETATE("VITAMIN_E");

    private final String nutrient;

    NutrientForm(String nutrient) { this.nutrient = nutrient; }

    /** 이 원료가 환산되는 성분 키(nutrients-master.json). */
    public String nutrient() { return nutrient; }
}
