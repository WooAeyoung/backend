package kr.co.wooaeyoung.standards;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 기준 데이터 파일(classpath:standards/) 무결성. 실제 배포되는 리소스를 그대로 읽는다. */
class StandardDataIntegrityTest {
    static final StandardParametersLoader LOADER = new StandardParametersLoader(new ObjectMapper().findAndRegisterModules(),
            Validation.buildDefaultValidatorFactory().getValidator());
    static final NutrientMaster MASTER = new NutrientMaster(LOADER);
    static final ProfileNutrients NIAS = ProfileNutrients.load(LOADER, StandardProfile.NIAS_V3);
    static final ProfileNutrients FEDIAF = ProfileNutrients.load(LOADER, StandardProfile.FEDIAF_2025);
    static final Set<String> NIAS_MINIMUM = Set.of("dog_early", "dog_late", "dog_adult", "cat_growth", "cat_adult");
    static final Set<String> NIAS_MAXIMUM = Set.of("dog_growth", "dog_adult", "cat_growth", "cat_adult");
    static final Set<String> CAUTION = Set.of("dog", "cat");
    static final Set<String> ALLOWED_UNITS = Set.of("g", "mg", "µg", "IU");
    /** 생애주기별로 함께 쓰는 (최소, 최대, 예외 주의값) 열. */
    static final List<String[]> NIAS_STAGES = List.of(new String[] {"dog_early", "dog_growth", "dog"}, new String[] {"dog_late", "dog_growth", "dog"},
            new String[] {"dog_adult", "dog_adult", "dog"}, new String[] {"cat_growth", "cat_growth", "cat"}, new String[] {"cat_adult", "cat_adult", "cat"});

    record Cell(String profile, String nutrient, String group, String column, StandardValue value) {
        String where() { return profile + "." + nutrient + "." + group + "." + column; }
    }

    static Stream<Cell> cells(ProfileNutrients file) {
        return file.nutrients().stream().flatMap(n -> Stream.of(
                n.minimum().entrySet().stream().map(e -> new Cell(file.profile().name(), n.key(), "minimum", e.getKey(), e.getValue())),
                n.maximum().entrySet().stream().map(e -> new Cell(file.profile().name(), n.key(), "maximum", e.getKey(), e.getValue())),
                n.caution().entrySet().stream().map(e -> new Cell(file.profile().name(), n.key(), "caution", e.getKey(), e.getValue()))).flatMap(s -> s));
    }

    // ---- 영양소 ID 무결성

    @Test void masterHas44ContiguousUniqueIdsAndUniqueKeys() {
        List<NutrientMaster.Entry> entries = MASTER.entries();
        assertEquals(44, entries.size());
        assertEquals(IntStream.rangeClosed(1, 44).boxed().toList(), entries.stream().map(NutrientMaster.Entry::id).toList());
        assertEquals(44, entries.stream().map(NutrientMaster.Entry::key).distinct().count());
    }

    @Test void existingKeysAreKeptAndOmega3IsFediafOnly() {
        Map<String, Integer> expected = Map.of("CALCIUM", 20, "PHOSPHORUS", 21, "ZINC", 31, "VITAMIN_D", 33, "VITAMIN_E", 34);
        expected.forEach((key, id) -> assertEquals(id, MASTER.find(key).orElseThrow().id(), key));
        assertTrue(MASTER.find("OMEGA3").isEmpty(), "OMEGA3는 44개 마스터에 없어야 한다");
        assertEquals(List.of("CALCIUM", "PHOSPHORUS", "VITAMIN_D", "VITAMIN_E", "OMEGA3", "ZINC"), FEDIAF.nutrients().stream().map(ProfileNutrients.Nutrient::key).toList());
        NutrientMeta omega3 = FEDIAF.analysisNutrients(MASTER).stream().filter(n -> n.id().equals("OMEGA3")).findFirst().orElseThrow();
        assertNull(omega3.nutrientNo());
        assertEquals("오메가3", omega3.name());
        // OMEGA3는 ALA(18)·EPA+DHA(19)와 별개 키다.
        assertEquals("ALPHA_LINOLENIC_ACID", MASTER.entries().get(17).key());
        assertEquals("EPA_DHA", MASTER.entries().get(18).key());
    }

    @Test void niasHasEveryMasterNutrientAndEveryLifeStageColumn() {
        assertEquals(MASTER.entries().stream().map(NutrientMaster.Entry::key).toList(), NIAS.nutrients().stream().map(ProfileNutrients.Nutrient::key).toList());
        for (ProfileNutrients.Nutrient nutrient : NIAS.nutrients()) {
            assertEquals(NIAS_MINIMUM, nutrient.minimum().keySet(), nutrient.key());
            assertEquals(NIAS_MAXIMUM, nutrient.maximum().keySet(), nutrient.key());
            assertEquals(CAUTION, nutrient.caution().keySet(), nutrient.key());
        }
    }

    @Test void exactly18NutrientsAreImplemented() {
        List<String> implemented = NIAS.nutrients().stream().filter(ProfileNutrients.Nutrient::implemented).map(ProfileNutrients.Nutrient::key).toList();
        assertEquals(List.of("TAURINE", "EPA_DHA", "CALCIUM", "PHOSPHORUS", "COPPER", "IODINE", "IRON", "MANGANESE", "SELENIUM", "ZINC",
                "VITAMIN_A", "VITAMIN_D", "VITAMIN_E", "VITAMIN_B1", "VITAMIN_B6", "VITAMIN_B12", "VITAMIN_B9", "VITAMIN_B7"), implemented);
    }

    // ---- 단위 무결성

    @Test void everyNutrientHasAnAllowedBaseUnit() {
        MASTER.entries().forEach(entry -> assertTrue(ALLOWED_UNITS.contains(entry.baseUnit()), entry.key() + "=" + entry.baseUnit()));
    }

    @Test void niasRecordUnitsMatchMasterBaseUnit() {
        cells(NIAS).forEach(cell -> {
            assertTrue(ALLOWED_UNITS.contains(cell.value().unit()), cell.where());
            assertEquals(MASTER.find(cell.nutrient()).orElseThrow().baseUnit(), cell.value().unit(), cell.where());
        });
    }

    /** FEDIAF는 기존 응답 단위(mg·µg)를 유지하므로 마스터 기준 단위가 아니라 FEDIAF 성분 단위와 맞춘다. */
    @Test void fediafRecordUnitsMatchFediafNutrientUnit() {
        Map<String, String> units = new HashMap<>();
        FEDIAF.nutrients().forEach(n -> units.put(n.key(), n.unit()));
        assertEquals(Map.of("CALCIUM", "mg", "PHOSPHORUS", "mg", "VITAMIN_D", "µg", "VITAMIN_E", "mg", "OMEGA3", "mg", "ZINC", "mg"), units);
        cells(FEDIAF).forEach(cell -> assertEquals(units.get(cell.nutrient()), cell.value().unit(), cell.where()));
    }

    // ---- 빈칸 무결성

    @Test void blankCellsStayNullAndNotApplicable() {
        Stream.concat(cells(NIAS), cells(FEDIAF)).forEach(cell -> {
            boolean blank = cell.value().value() == null;
            assertEquals(blank, cell.value().verificationStatus() == VerificationStatus.NOT_APPLICABLE, cell.where());
            if (blank) assertEquals(StandardValue.TABLE_NOT_APPLICABLE, cell.value().table(), cell.where());
            if (blank) assertNotNull(cell.value().note(), cell.where() + " 빈칸 사유(note) 필요");
        });
        // 모든 칸이 빈 비타민 K도 삭제하지 않는다.
        assertTrue(cells(NIAS).filter(cell -> cell.nutrient().equals("VITAMIN_K")).allMatch(cell -> cell.value().value() == null));
        assertEquals(11, cells(NIAS).filter(cell -> cell.nutrient().equals("VITAMIN_K")).count());
        // 0이 아니라 null: 기준값으로 0을 쓴 칸이 없다.
        assertTrue(Stream.concat(cells(NIAS), cells(FEDIAF)).noneMatch(cell -> Objects.equals(cell.value().value(), 0d)));
    }

    @Test void fediafVitaminEAndOmega3AreNotApplicableWithReason() {
        cells(FEDIAF).filter(cell -> Set.of("VITAMIN_E", "OMEGA3").contains(cell.nutrient()) && !cell.group().equals("caution")).forEach(cell -> {
            assertEquals("FEDIAF Nutritional Guidelines 2025", cell.value().sourceDocument(), cell.where());
            assertTrue(cell.value().note().contains("단위·성분 정의 불일치로 적용 안 함"), cell.where());
        });
    }

    // ---- 출처 무결성

    @Test void everyRecordHasSourceTableAndStatus() {
        Stream.concat(cells(NIAS), cells(FEDIAF)).forEach(cell -> {
            assertFalse(cell.value().sourceDocument() == null || cell.value().sourceDocument().isBlank(), cell.where());
            assertFalse(cell.value().table() == null || cell.value().table().isBlank(), cell.where());
            assertNotNull(cell.value().verificationStatus(), cell.where());
            assertNotNull(cell.value().valueType(), cell.where());
            assertEquals("per_1000kcal_ME", cell.value().basis(), cell.where());
        });
    }

    @Test void unconfirmedTableIsAlwaysPending() {
        List<Cell> unconfirmed = Stream.concat(cells(NIAS), cells(FEDIAF)).filter(cell -> cell.value().table().equals(StandardValue.TABLE_UNCONFIRMED)).toList();
        assertFalse(unconfirmed.isEmpty());
        unconfirmed.forEach(cell -> assertEquals(VerificationStatus.PENDING_VERIFICATION, cell.value().verificationStatus(), cell.where()));
    }

    @Test void niasValuesArePendingAndFediafValuesAreVerified() {
        cells(NIAS).filter(cell -> cell.value().value() != null)
                .forEach(cell -> assertEquals(VerificationStatus.PENDING_VERIFICATION, cell.value().verificationStatus(), cell.where()));
        cells(FEDIAF).filter(cell -> cell.value().value() != null).forEach(cell -> {
            assertEquals(VerificationStatus.VERIFIED, cell.value().verificationStatus(), cell.where());
            assertNotNull(cell.value().verifiedAt(), cell.where());
        });
    }

    @Test void niasTablesFollowColumnSource() {
        Map<String, String> tables = Map.of("dog_early", "2-17c", "dog_late", "2-17c", "dog_growth", "2-17c", "dog_adult", "2-17d", "cat_growth", "2-17g", "cat_adult", "2-17h");
        cells(NIAS).filter(cell -> cell.value().value() != null && !cell.group().equals("caution"))
                .forEach(cell -> assertEquals(tables.get(cell.column()), cell.value().table(), cell.where()));
    }

    // ---- 값 관계

    @Test void minimumCautionMaximumAreOrdered() {
        for (ProfileNutrients.Nutrient nutrient : NIAS.nutrients())
            for (String[] stage : NIAS_STAGES) {
                Double min = nutrient.minimum().get(stage[0]).value(), max = nutrient.maximum().get(stage[1]).value(), caution = nutrient.caution().get(stage[2]).value();
                String where = nutrient.key() + " " + Arrays.toString(stage);
                if (min != null && max != null) assertTrue(min <= max, where);
                if (min != null && caution != null) assertTrue(min <= caution, where);
                if (caution != null && max != null) assertTrue(caution <= max, where);
            }
    }

    @Test void cautionOnlyNutrientsHaveACautionLine() {
        for (String key : List.of("COPPER", "IRON", "MANGANESE", "ZINC"))
            for (String species : List.of("dog", "cat")) {
                ProfileNutrients.Nutrient nutrient = NIAS.nutrient(key);
                assertNotNull(nutrient.caution().get(species).value(), key + " " + species);
                assertNull(nutrient.maximum().get(species + "_adult").value(), key + " " + species);
            }
        assertEquals(142d, NIAS.nutrient("SELENIUM").caution().get("cat").value());
        assertNull(NIAS.nutrient("SELENIUM").maximum().get("cat_adult").value());
    }

    // ---- 매개변수

    @Test void niasParametersMatchTheParameterSheet() {
        NiasV3Parameters p = LOADER.load(StandardProfile.NIAS_V3, NiasV3Parameters.class);
        assertEquals(110d, p.energy().dogAdultK()); assertEquals(100d, p.energy().catAdultK());
        assertEquals(2.25, p.energy().catStage1Multiplier()); assertEquals(1.875, p.energy().catStage2Multiplier()); assertEquals(1.5, p.energy().catStage3Multiplier());
        assertEquals(31.75, p.largeBreedCalcium().expectedAdultWeightAtLeastKg()); assertEquals(4.5, p.largeBreedCalcium().dogGrowthCalciumMaximum());
        assertEquals(1d, p.calciumPhosphorus().minimum()); assertEquals(2d, p.calciumPhosphorus().maximum());
        assertEquals(10d, p.recommendation().w2()); assertEquals(5d, p.recommendation().w3()); assertEquals(1d, p.recommendation().w4());
        assertEquals(3, p.recommendation().topN()); assertEquals(1d, p.feedEstimate().feedFactor());
        assertEquals(5, p.growthCurve().sizeClasses().size()); assertNull(p.growthCurve().sizeClasses().get("XXL").upperKg());
    }

    @Test void fediafParametersPreserveJavaValues() {
        FediafParameters p = LOADER.load(StandardProfile.FEDIAF_2025, FediafParameters.class);
        assertEquals(95d, p.energy().dogAdultK()); assertEquals(75d, p.energy().catAdultK()); assertEquals(100d, p.energy().catGrowthK());
        assertEquals(0d, p.recommendation().w3()); assertTrue(p.recommendation().note().contains("현재 Java 동작 보존용"));
    }

    // ---- 패키징

    @Test void standardResourcesAreOnTheClasspath() {
        for (String location : List.of("standards/nutrients-master.json", "standards/nias-v3/nutrients.json", "standards/nias-v3/parameters.json",
                "standards/fediaf-2025/nutrients.json", "standards/fediaf-2025/parameters.json"))
            assertTrue(new ClassPathResource(location).exists(), location);
    }

    // ---- 잘못된 데이터는 적재 단계에서 거부

    @Test void unconfirmedVerifiedRecordFailsIntegrityCheck() {
        assertIntegrityFailure("standards-invalid/nias-nutrients-unconfirmed-verified.json", "UNCONFIRMED");
    }

    @Test void cautionBelowMinimumFailsIntegrityCheck() {
        assertIntegrityFailure("standards-invalid/nias-nutrients-caution-below-minimum.json", "최소 > 주의");
    }

    @Test void blankValueMustBeNotApplicable() {
        assertIntegrityFailure("standards-invalid/nias-nutrients-blank-not-na.json", "빈칸은 NOT_APPLICABLE");
    }

    private static void assertIntegrityFailure(String location, String expectedMessage) {
        ProfileNutrients file = LOADER.load(location, ProfileNutrients.class);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> file.check(MASTER, NIAS_MINIMUM, NIAS_MAXIMUM, CAUTION,
                List.of(new ProfileNutrients.StageColumns("dog_adult", "dog_adult", "dog")), true));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }
}
