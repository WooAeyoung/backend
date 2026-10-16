package kr.co.wooaeyoung.standards;

/** 분석 응답의 성분 행에 붙는 출처 블록. 기준값을 숨긴 행에도 그대로 보여 준다. */
public record StandardReference(
        String sourceDocument,
        String table,
        Integer sourceYear,
        VerificationStatus verificationStatus,
        ValueType valueType,
        String unit,
        String basis,
        String note) {
    public static StandardReference of(StandardValue value) {
        return new StandardReference(value.sourceDocument(), value.table(), value.sourceYear(), value.verificationStatus(),
                value.valueType(), value.unit(), value.basis(), value.note());
    }
}
