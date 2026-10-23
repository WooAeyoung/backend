package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.standards.Limit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 계산명세서 7장 judge의 순서와 "세 선이 모두 null일 때만 기준 없음" 규칙. */
class NutritionServiceClassifyTest {
    @Test void noStandardOnlyWhenAllThreeLinesAreNull() {
        assertEquals("NO_STANDARD", NutritionService.classify(5, new Limit(null, null, null)));
    }

    @Test void cautionOnlyLineIsJudgedInsteadOfNoStandard() {
        Limit cautionOnly = new Limit(null, 10d, null);
        assertEquals("ADEQUATE_NO_UPPER_LIMIT", NutritionService.classify(9.99, cautionOnly));
        assertEquals("CAUTION", NutritionService.classify(10, cautionOnly));
        assertEquals("CAUTION", NutritionService.classify(1000, cautionOnly));
    }

    @Test void orderIsDeficientThenExcessThenCaution() {
        Limit line = new Limit(1d, 3d, 4d);
        assertEquals("DEFICIENT", NutritionService.classify(0.99, line));
        assertEquals("ADEQUATE", NutritionService.classify(1, line));
        assertEquals("CAUTION", NutritionService.classify(3, line));
        assertEquals("CAUTION", NutritionService.classify(4, line));
        assertEquals("EXCESS", NutritionService.classify(4.01, line));
        Limit minimumAndCaution = new Limit(1d, 3d, null);
        assertEquals("ADEQUATE_NO_UPPER_LIMIT", NutritionService.classify(2, minimumAndCaution));
        assertEquals("CAUTION", NutritionService.classify(3, minimumAndCaution));
    }
}
