package kr.co.wooaeyoung.standards;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 기준값 한 칸(1000 kcal ME당). 빈칸은 value=null, verificationStatus=NOT_APPLICABLE로 보존한다.
 * table을 원문에서 확인할 수 없으면 "UNCONFIRMED"이며, 그 레코드는 PENDING_VERIFICATION이어야 한다.
 */
public record StandardValue(
        Double value,
        @NotBlank String unit,
        @NotBlank String basis,
        @NotBlank String sourceDocument,
        @NotBlank String table,
        Integer sourceYear,
        @NotNull VerificationStatus verificationStatus,
        LocalDate verifiedAt,
        @NotNull ValueType valueType,
        String note) {
    public static final String TABLE_UNCONFIRMED = "UNCONFIRMED";
    public static final String TABLE_NOT_APPLICABLE = "N/A";
}
