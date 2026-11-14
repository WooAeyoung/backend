package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** BE-V3-012 체중 구간 경계: 구간은 (이전 maxKg, maxKg]이고 경계값은 아래 구간에 속한다(계산명세서 10.2 "weight_kg 이하 첫 구간"). */
class NiasDoseByWeightTest {
    private static final CatalogService.Product THREE_BANDS = product(List.of(
            new CatalogService.DoseBand(5d, 1), new CatalogService.DoseBand(15d, 2), new CatalogService.DoseBand(null, 3)), 9d);

    @ParameterizedTest(name = "{0} kg → 구간 {2}, dose {1}")
    @CsvSource({
            "0.1,    1, 0",
            "4.999,  1, 0",
            "5,      1, 0",
            "5.001,  2, 1",
            "14.999, 2, 1",
            "15,     2, 1",
            "15.001, 3, 2",
            "100,    3, 2"})
    void bandBoundaries(double weightKg, double dose, int band) {
        NiasDose selected = NiasDose.of(THREE_BANDS, weightKg);
        assertEquals(dose, selected.dose());
        assertEquals(band, selected.bandIndex());
        assertEquals(NiasDose.DOSE_BY_WEIGHT, selected.source());
        assertFalse(selected.beyondLastBand());
        // 급여량은 기존 doseForWeight와 같다.
        assertEquals(THREE_BANDS.doseForWeight(weightKg), selected.dose());
    }

    @Test void bandLimitsAreReported() {
        NiasDose first = NiasDose.of(THREE_BANDS, 5), middle = NiasDose.of(THREE_BANDS, 15), open = NiasDose.of(THREE_BANDS, 15.001);
        assertNull(first.aboveKg()); assertEquals(5d, first.maxKg());
        assertEquals(5d, middle.aboveKg()); assertEquals(15d, middle.maxKg());
        assertEquals(15d, open.aboveKg()); assertNull(open.maxKg());
        Map<String, Object> map = open.toMap();
        assertEquals(List.of("source", "bandIndex", "aboveKg", "maxKg", "dose", "beyondLastBand"), List.copyOf(map.keySet()));
        assertEquals(3d, map.get("dose"));
    }

    @Test void weightAboveEveryClosedBandUsesLastBand() {
        CatalogService.Product closed = product(List.of(new CatalogService.DoseBand(10d, 1), new CatalogService.DoseBand(20d, 2)), null);
        NiasDose selected = NiasDose.of(closed, 20.001);
        assertEquals(2d, selected.dose());
        assertEquals(1, selected.bandIndex());
        assertTrue(selected.beyondLastBand());
        assertFalse(NiasDose.of(closed, 20).beyondLastBand());
    }

    @Test void productWithoutDoseByWeightKeepsRecommendedDailyAmount() {
        NiasDose selected = NiasDose.of(product(List.of(), 2.5), 30);
        assertEquals(2.5, selected.dose());
        assertEquals(NiasDose.RECOMMENDED_DAILY_AMOUNT, selected.source());
        assertNull(selected.bandIndex());
    }

    /** products.json 영양제는 10 kg 이하 1회, 초과 2회. */
    @ParameterizedTest
    @CsvSource({"9.999, 1", "10, 1", "10.001, 2"})
    void catalogSupplementsUseTenKilogramBoundary(double weightKg, double dose) throws Exception {
        CatalogService catalog = new CatalogService(new ObjectMapper());
        for (CatalogService.Product product : catalog.all()) {
            if (!product.type().equals("SUPPLEMENT")) continue;
            assertEquals(dose, NiasDose.of(product, weightKg).dose(), product.id());
        }
    }

    private static CatalogService.Product product(List<CatalogService.DoseBand> bands, Double recommendedDailyAmount) {
        return new CatalogService.Product("test", null, "테스트", "데모", "SUPPLEMENT", 1, "TABLET", "COMPLETE",
                Map.of("ZINC", 1d), recommendedDailyAmount, bands);
    }
}
