package kr.co.wooaeyoung.units;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 환산 기록. value는 기준 단위(nutrients-master.json baseUnit)이고 반올림하지 않는다.
 * perGram이면 제품 1 g당, 아니면 1회 제공량당 값이다.
 */
public record Conversion(Status status, Double value, boolean perGram,
                         double originalValue, String originalUnit, String basis, String form,
                         String baseUnit, String formula, String source, String note) {
    public enum Status {
        /** 기준 단위로 환산함. */
        CONVERTED,
        /** 라벨에 실제 함량이 없음(보증값, 총 오메가3만 표기). 정보 없음으로 취급한다. */
        NO_VALUE,
        /** 종 규칙으로 더하지 않음(고양이 베타카로틴). 정보 없음으로 취급한다. */
        EXCLUDED,
        /** 원료 형태·수분·열량 등 환산에 필요한 정보가 없음. 추정하지 않는다. */
        INSUFFICIENT_DATA,
        /** 환산표에 없는 단위 조합(예: 질량 성분에 IU). */
        UNIT_CONVERSION_UNSUPPORTED
    }

    public boolean converted() { return status == Status.CONVERTED; }

    /** 값을 쓸 수 없고 7.1의 "상세 값 없음"으로 보는 경우. */
    public boolean treatedAsMissing() { return status == Status.NO_VALUE || status == Status.EXCLUDED; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status.name()); map.put("value", value); map.put("perGram", perGram);
        map.put("originalValue", originalValue); map.put("originalUnit", originalUnit); map.put("basis", basis);
        map.put("form", form); map.put("baseUnit", baseUnit); map.put("formula", formula);
        map.put("source", source); map.put("note", note);
        return map;
    }
}