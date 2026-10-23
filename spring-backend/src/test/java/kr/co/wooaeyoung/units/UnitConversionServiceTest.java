package kr.co.wooaeyoung.units;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.standards.NutrientMaster;
import kr.co.wooaeyoung.standards.StandardParametersLoader;
import kr.co.wooaeyoung.units.Conversion.Status;
import kr.co.wooaeyoung.units.LabelValue.Guarantee;
import kr.co.wooaeyoung.units.UnitConversionService.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** BE-V3-010 단위 환산(처리기준 v3 7.3). 기대값은 처리기준 표의 계수로 손계산한 값이다. */
class UnitConversionServiceTest {
    static final StandardParametersLoader LOADER = new StandardParametersLoader(new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator());
    static final UnitConversionFactors FACTORS = new UnitConversionFactors(LOADER);
    static final UnitConversionService SERVICE = new UnitConversionService(FACTORS, new NutrientMaster(LOADER));
    static final Context DOG = new Context(Species.DOG, null, null), CAT = new Context(Species.CAT, null, null);

    static Conversion convert(String nutrient, double amount, String unit) { return SERVICE.convert(nutrient, LabelValue.of(amount, unit), DOG); }
    static Conversion convert(String nutrient, double amount, String unit, LabelBasis basis, NutrientForm form, Context context) {
        return SERVICE.convert(nutrient, new LabelValue(amount, unit, basis, form, null), context);
    }
    static void converted(double expected, Conversion conversion) {
        assertEquals(Status.CONVERTED, conversion.status(), conversion.toString());
        assertEquals(expected, conversion.value(), Math.abs(expected) * 1e-12, conversion.toString());
    }
    static void insufficient(Conversion conversion) {
        assertEquals(Status.INSUFFICIENT_DATA, conversion.status(), conversion.toString());
        assertNull(conversion.value());
        assertNotNull(conversion.note());
    }

    // ---- 접두 단위와 µ 표기

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource({
            "CALCIUM, 900, mg, 0.9",       // 기준 g
            "CALCIUM, 1, g, 1",
            "CALCIUM, 500000, µg, 0.5",
            "COPPER, 1, g, 1000",          // 기준 mg
            "ZINC, 2000, µg, 2",
            "ZINC, 12, MG, 12",
            "SELENIUM, 0.05, mg, 50",      // 기준 µg
            "SELENIUM, 0.000001, g, 1",
    })
    void prefixUnits(String nutrient, double amount, String unit, double expected) {
        Conversion conversion = convert(nutrient, amount, unit);
        converted(expected, conversion);
        assertFalse(conversion.perGram());
        assertEquals("PER_SERVING", conversion.basis());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"µg", "μg", "mcg", "ug", "UG", "MCG", " mcg "})
    @DisplayName("µ 표기 정규화: U+00B5, U+03BC, mcg, ug 모두 µg")
    void microSpellings(String unit) {
        Conversion conversion = convert("SELENIUM", 10, unit);
        converted(10, conversion);
        assertEquals("µg", conversion.baseUnit());
        assertEquals(unit, conversion.originalUnit());
    }

    @Test void iuLabelIsKeptAsIs() {
        converted(400, convert("VITAMIN_D", 400, "iu"));
        converted(20, convert("VITAMIN_E", 20, "IU"));
        assertTrue(convert("VITAMIN_E", 20, "IU").formula().contains("그대로"));
    }

    @Test void unsupportedUnits() {
        assertEquals(Status.UNIT_CONVERSION_UNSUPPORTED, convert("CALCIUM", 100, "IU").status());
        assertEquals(Status.UNIT_CONVERSION_UNSUPPORTED, convert("VITAMIN_D", 1, "CFU").status());
        assertNull(convert("VITAMIN_D", 1, "CFU").value());
    }

    // ---- 라벨 표기 형태

    @Test void per100Gram() {
        Conversion conversion = convert("ZINC", 15, "mg", LabelBasis.PER_100G, null, DOG);
        converted(0.15, conversion);
        assertTrue(conversion.perGram());
        assertEquals("15 mg ÷ 100 = 0.15 mg/g", conversion.formula());
    }

    @Test void percentIsGramsPerGram() {
        Conversion conversion = convert("CALCIUM", 1.2, "%");
        converted(0.012, conversion);
        assertTrue(conversion.perGram());
        assertEquals("PERCENT", conversion.basis());
        converted(12, convert("ZINC", 1.2, "%"));   // 1.2 % = 0.012 g/g = 12 mg/g
    }

    @Test void perKilogramAndPpm() {
        converted(0.15, convert("ZINC", 150, "mg", LabelBasis.PER_KG, null, DOG));
        Conversion ppm = convert("ZINC", 150, "ppm");
        converted(0.15, ppm);
        assertEquals("PER_KG", ppm.basis());
        converted(15, convert("VITAMIN_A", 15000, "IU", LabelBasis.PER_KG, null, DOG));
    }

    @Test void per1000KcalNeedsEnergy() {
        Context withEnergy = new Context(Species.DOG, null, 3800d);
        Conversion conversion = convert("CALCIUM", 2.5, "g", LabelBasis.PER_1000KCAL, null, withEnergy);
        converted(2.5 * 3800 / 1000 / 1000, conversion);
        assertTrue(conversion.perGram());
        converted(0.0095, conversion);
        insufficient(convert("CALCIUM", 2.5, "g", LabelBasis.PER_1000KCAL, null, DOG));
        assertTrue(convert("CALCIUM", 2.5, "g", LabelBasis.PER_1000KCAL, null, DOG).note().contains("대사에너지"));
    }

    @Test void dryMatterNeedsMoisture() {
        Context moisture = new Context(Species.DOG, 10d, null);
        Conversion conversion = convert("CALCIUM", 1.5, "%", LabelBasis.DRY_MATTER_PERCENT, null, moisture);
        converted(1.5 * (1 - 10d / 100) / 100, conversion);
        converted(0.0135, conversion);
        insufficient(convert("CALCIUM", 1.5, "%", LabelBasis.DRY_MATTER_PERCENT, null, DOG));
        assertTrue(convert("CALCIUM", 1.5, "%", LabelBasis.DRY_MATTER_PERCENT, null, DOG).note().contains("수분"));
    }

    @Test void guaranteedValuesAreNotActualContent() {
        for (Guarantee guarantee : Guarantee.values()) {
            Conversion conversion = SERVICE.convert("CALCIUM", new LabelValue(0.04, "%", null, null, guarantee), DOG);
            assertEquals(Status.NO_VALUE, conversion.status());
            assertNull(conversion.value());
            assertTrue(conversion.treatedAsMissing());
            assertTrue(conversion.note().contains("보증값"));
        }
    }

    @Test void contradictoryInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> convert("CALCIUM", -1, "mg"));
        assertThrows(IllegalArgumentException.class, () -> convert("VITAMIN_D", 10, "µg", null, NutrientForm.RETINOL, DOG));
        assertThrows(IllegalArgumentException.class, () -> convert("CALCIUM", 1, "mg", LabelBasis.PERCENT, null, DOG));
        assertThrows(IllegalArgumentException.class, () -> convert("CALCIUM", 1, "%", LabelBasis.PER_KG, null, DOG));
        assertThrows(IllegalArgumentException.class, () -> convert("UNKNOWN", 1, "mg"));
        assertThrows(IllegalArgumentException.class, () -> convert("CALCIUM", 1, "%", LabelBasis.DRY_MATTER_PERCENT, null, new Context(Species.DOG, 100d, null)));
    }

    // ---- 비타민 IU 환산(영양표준 표 7-5)

    @ParameterizedTest(name = "{0} {1} {2} {3} → {4} IU")
    @CsvSource({
            "VITAMIN_A, RETINOL, 300, µg, 1000",
            "VITAMIN_A, RETINOL, 0.3, µg, 1",
            "VITAMIN_A, RETINYL_ACETATE, 344, µg, 1000",
            "VITAMIN_A, RETINYL_PROPIONATE, 359, µg, 1000",
            "VITAMIN_A, RETINYL_PALMITATE, 550, µg, 1000",
            "VITAMIN_A, RETINOL_EQUIVALENT, 100, µg, 333",
            "VITAMIN_A, BETA_CAROTENE, 2, mg, 1666",
            "VITAMIN_D, CHOLECALCIFEROL, 10, µg, 400",
            "VITAMIN_D, CHOLECALCIFEROL, 0.025, µg, 1",
            "VITAMIN_E, DL_ALPHA_TOCOPHERYL_ACETATE, 10, mg, 10",
            "VITAMIN_E, D_ALPHA_TOCOPHEROL, 10, mg, 14.9",
            "VITAMIN_E, D_ALPHA_TOCOPHERYL_ACETATE, 10, mg, 13.6",
            "VITAMIN_E, DL_ALPHA_TOCOPHEROL, 10, mg, 11",
    })
    void iuByForm(String nutrient, NutrientForm form, double amount, String unit, double expectedIu) {
        Conversion conversion = convert(nutrient, amount, unit, null, form, DOG);
        converted(expectedIu, conversion);
        assertEquals("IU", conversion.baseUnit());
        assertEquals(form.name(), conversion.form());
        assertTrue(conversion.source().contains("표 7-5"), conversion.source());
        assertNull(conversion.note());
    }

    @Test @DisplayName("레티놀 1 mg = 1000/0.3 IU (3,333.33…), 반올림하지 않음")
    void retinolMilligramIsNotRounded() {
        Conversion conversion = convert("VITAMIN_A", 1, "mg", null, NutrientForm.RETINOL, DOG);
        assertEquals(1000 / 0.3, conversion.value(), 0);
        assertEquals("1 mg = 1000 µg; 1000 µg × 1 IU ÷ 0.3 µg = " + UnitConversionService.plain(1000 / 0.3) + " IU", conversion.formula());
    }

    @Test @DisplayName("원료 미상: 간주 값을 고르지 않으면 A·D·E 모두 INSUFFICIENT_DATA")
    void missingFormIsInsufficientData() {
        insufficient(convert("VITAMIN_A", 300, "µg"));
        insufficient(convert("VITAMIN_A", 1, "mg"));
        insufficient(convert("VITAMIN_D", 10, "µg"));
        insufficient(convert("VITAMIN_E", 10, "mg"));
        assertTrue(convert("VITAMIN_E", 10, "mg").note().contains("원료 형태"));
    }

    @Test @DisplayName("원료 미상 간주 값을 명시하면 처리기준 계수로 환산하고 note를 남김")
    void explicitAssumptionsConvertWithNote() {
        Conversion e = convert("VITAMIN_E", 10, "mg", null, NutrientForm.UNKNOWN_ASSUME_DL_ALPHA_TOCOPHERYL_ACETATE, DOG);
        converted(10, e);
        assertEquals("원료 미상, dl-알파-토코페롤 아세테이트로 간주", e.note());
        Conversion d = convert("VITAMIN_D", 10, "mcg", null, NutrientForm.UNKNOWN_ASSUME_CHOLECALCIFEROL, DOG);
        converted(400, d);
        assertEquals("원료 미상, D3(콜레칼시페롤)로 간주", d.note());
        Conversion a = convert("VITAMIN_A", 100, "µg", null, NutrientForm.UNKNOWN_ASSUME_RETINOL_EQUIVALENT, DOG);
        converted(333, a);
        assertEquals("원료 미상, 레티놀 당량(RE)으로 간주", a.note());
    }

    @Test @DisplayName("고양이 베타카로틴은 비타민 A에 더하지 않음(EXCLUDED), 종이 없으면 INSUFFICIENT_DATA")
    void catBetaCarotene() {
        Conversion cat = convert("VITAMIN_A", 2, "mg", null, NutrientForm.BETA_CAROTENE, CAT);
        assertEquals(Status.EXCLUDED, cat.status());
        assertNull(cat.value());
        assertTrue(cat.treatedAsMissing());
        assertTrue(cat.note().contains("고양이"));
        insufficient(convert("VITAMIN_A", 2, "mg", null, NutrientForm.BETA_CAROTENE, Context.NONE));
    }

    @Test void iuFormWithLabelBasis() {
        Conversion conversion = convert("VITAMIN_D", 25, "µg", LabelBasis.PER_100G, NutrientForm.CHOLECALCIFEROL, DOG);
        converted(10, conversion);   // 25 µg/100 g = 0.25 µg/g = 10 IU/g
        assertTrue(conversion.perGram());
    }

    // ---- EPA+DHA

    @Test void epaAndDhaAreSummed() {
        Conversion epa = convert("EPA_DHA", 120, "mg"), dha = convert("EPA_DHA", 80, "mg");
        Conversion combined = SERVICE.combineEpaDha(epa, dha, 300d, "mg").orElseThrow();
        converted(0.2, combined);
        assertEquals(200, combined.originalValue());
        assertEquals("g", combined.baseUnit());
        assertTrue(combined.formula().contains("EPA 0.12 + DHA 0.08 = 0.2 g"), combined.formula());
    }

    @Test @DisplayName("총 오메가3만 있으면 EPA+DHA는 정보 없음(값을 쓰지 않음)")
    void totalOmega3OnlyIsNoValue() {
        Conversion combined = SERVICE.combineEpaDha(null, null, 300d, "mg").orElseThrow();
        assertEquals(Status.NO_VALUE, combined.status());
        assertNull(combined.value());
        assertTrue(combined.note().contains("총 오메가3"));
        assertEquals(Optional.empty(), SERVICE.combineEpaDha(null, null, null, null));
    }

    @Test void singleComponentIsUsedWithNote() {
        Conversion combined = SERVICE.combineEpaDha(null, convert("EPA_DHA", 80, "mg"), null, null).orElseThrow();
        converted(0.08, combined);
        assertTrue(combined.note().contains("EPA 미표기"));
    }

    @Test void insufficientComponentPropagates() {
        Context noEnergy = DOG;
        Conversion epa = convert("EPA_DHA", 1, "g", LabelBasis.PER_1000KCAL, null, noEnergy);
        assertEquals(Status.INSUFFICIENT_DATA, SERVICE.combineEpaDha(epa, convert("EPA_DHA", 80, "mg"), null, null).orElseThrow().status());
    }

    // ---- 계수표 무결성

    @Test void factorTableRejectsMissingForm() {
        UnitConversionFactors.File file = LOADER.load(UnitConversionFactors.LOCATION, UnitConversionFactors.File.class);
        List<UnitConversionFactors.IuFactor> factors = new ArrayList<>(file.iuFactors());
        factors.removeIf(factor -> factor.form() == NutrientForm.CHOLECALCIFEROL);
        UnitConversionFactors.File broken = new UnitConversionFactors.File(file.version(), file.sourceDocument(), file.massUnits(),
                file.unitAliases(), file.labelBases(), factors, file.omega3());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new UnitConversionFactors(broken));
        assertTrue(error.getMessage().contains("원료 계수 없음: CHOLECALCIFEROL"), error.getMessage());
    }

    @Test void everyFactorHasSource() {
        UnitConversionFactors.File file = LOADER.load(UnitConversionFactors.LOCATION, UnitConversionFactors.File.class);
        file.iuFactors().forEach(factor -> assertTrue(factor.source().contains("처리기준 v3 7.3(4)"), factor.form().name()));
        file.labelBases().forEach(basis -> assertTrue(basis.source().contains("처리기준 v3 7.3(3)"), basis.basis().name()));
    }
}