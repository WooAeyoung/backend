package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.domain.standard.*;
import kr.co.wooaeyoung.domain.standard.StandardModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 영양소 마스터·기준값 레코드 무결성과 빈칸(null) 보존. */
class StandardsIntegrityTest {
    private static final Set<String> PRIORITY = Set.of("PROTEIN", "FAT", "LINOLEIC_ACID", "ALPHA_LINOLENIC_ACID", "EPA_DHA", "CALCIUM",
            "PHOSPHORUS", "POTASSIUM", "SODIUM", "MAGNESIUM", "IRON", "COPPER", "ZINC", "SELENIUM", "VITAMIN_A", "VITAMIN_D", "VITAMIN_E", "TAURINE");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StandardLoader.Source CLASSPATH = path -> new ClassPathResource("standards/" + path).getContentAsString(StandardCharsets.UTF_8);
    private static final StandardRegistry REGISTRY = new StandardRegistry(MAPPER, CLASSPATH, false);

    @Test void masterHas44UniqueNutrientsWithValidIdsAndUnits() {
        List<NutrientMaster> nutrients = REGISTRY.catalog().nutrients();
        assertThat(nutrients).hasSize(44);
        assertThat(nutrients.stream().map(NutrientMaster::id).distinct()).hasSize(44);
        assertThat(nutrients).allMatch(nutrient -> StandardModels.NUTRIENT_ID.matcher(nutrient.id()).matches());
        assertThat(nutrients).allMatch(nutrient -> StandardModels.UNITS.contains(nutrient.baseUnit()));
        assertThat(nutrients.stream().map(NutrientMaster::sortOrder).sorted().toList()).isEqualTo(IntStream.rangeClosed(1, 44).boxed().toList());
    }

    @Test void exactly18PriorityNutrients() {
        assertThat(REGISTRY.catalog().nutrients().stream().filter(NutrientMaster::prioritySupported).map(NutrientMaster::id)
                .collect(Collectors.toSet())).isEqualTo(PRIORITY);
    }

    @Test void fediafOmega3IsProfileSpecificAndNotMappedToNias() {
        assertThat(REGISTRY.catalog().nutrients()).noneMatch(nutrient -> nutrient.id().equals("OMEGA3"));
        assertThat(REGISTRY.masterNutrients(StandardProfileId.FEDIAF_2025)).containsKey("OMEGA3");
        assertThat(REGISTRY.masterNutrients(StandardProfileId.NIAS_V3)).doesNotContainKey("OMEGA3");
    }

    @Test void legacyFediafIdsAreKept() {
        assertThat(REGISTRY.profile(StandardProfileId.FEDIAF_2025).nutrients().stream().map(ProfileNutrient::id).toList())
                .containsExactly("CALCIUM", "PHOSPHORUS", "VITAMIN_D", "VITAMIN_E", "OMEGA3", "ZINC");
    }

    @ParameterizedTest @EnumSource(StandardProfileId.class)
    void recordsReferenceMasterAndProfileStagesWithSources(StandardProfileId id) {
        StandardProfile profile = REGISTRY.profile(id);
        Map<String, NutrientMaster> known = REGISTRY.masterNutrients(id);
        for (StandardRecord record : profile.records()) {
            assertThat(known).containsKey(record.nutrientId());
            assertThat(profile.recordStages()).contains(record.lifeStage());
            assertThat(record.sourceDocument()).isNotBlank();
            assertThat(record.basis()).isNotBlank();
            if (record.min() != null || record.max() != null)
                assertThat(UnitConversion.convertible(record.unit(), known.get(record.nutrientId()).baseUnit(), known.get(record.nutrientId()))).isTrue();
            if (record.min() != null && record.max() != null) assertThat(record.min()).isLessThanOrEqualTo(record.max());
        }
    }

    @Test void niasHasEveryMasterNutrientForEveryStageAndNoValues() {
        StandardProfile profile = REGISTRY.profile(StandardProfileId.NIAS_V3);
        assertThat(profile.records()).hasSize(44 * profile.recordStages().size());
        // 사용자가 원문 확인 값을 주기 전까지 수치는 비워 두고 미검증으로 둔다.
        assertThat(profile.records()).allMatch(record -> record.min() == null && record.max() == null
                && record.verificationStatus() == VerificationStatus.UNVERIFIED && record.sourceTable() != null);
    }

    @Test void fediafRecordsAreVerifiedOrNotApplicable() {
        for (StandardRecord record : REGISTRY.profile(StandardProfileId.FEDIAF_2025).records()) {
            assertThat(record.verificationStatus()).isIn(VerificationStatus.VERIFIED, VerificationStatus.NOT_APPLICABLE);
            if (record.verificationStatus() == VerificationStatus.VERIFIED) assertThat(record.sourceTable()).isNotNull();
        }
    }

    @Test void blankCellsAreReadAsNullNotZero() {
        assertThat(REGISTRY.record(StandardProfileId.FEDIAF_2025, "ZINC", "DOG_ADULT").max()).isNull();
        assertThat(REGISTRY.record(StandardProfileId.FEDIAF_2025, "ZINC", "DOG_ADULT").min()).isEqualTo(20.8);
    }

    @Test void profilesAreReadOnly() {
        StandardProfile profile = REGISTRY.profile(StandardProfileId.FEDIAF_2025);
        assertThatThrownBy(() -> profile.params().put("energyKDogAdult", null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> profile.records().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> REGISTRY.profiles().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** Python(data/standards)과 Java(resources/standards) 복사본이 같아야 한다. */
    @Test void springResourcesMatchPythonData() throws IOException {
        Path python = Path.of("..", "data", "standards");
        assumeTrue(Files.isDirectory(python), "Python 데이터 폴더가 없는 빌드(예: Docker)에서는 건너뜀");
        for (String file : List.of("nutrients.json", "fediaf_2025/profile.json", "fediaf_2025/records.csv", "nias_v3/profile.json", "nias_v3/records.csv"))
            assertThat(normalize(CLASSPATH.read(file))).as(file).isEqualTo(normalize(Files.readString(python.resolve(file), StandardCharsets.UTF_8)));
    }

    private static String normalize(String text) { return text.replace("\r\n", "\n"); }

    // ---------------------------------------------------------------- 오류 데이터는 적재 실패

    private static StandardLoader.Source modified(Path dir, UnaryOperator<List<String>> change) throws IOException {
        Path records = dir.resolve("fediaf_2025/records.csv");
        Files.createDirectories(records.getParent());
        List<String> lines = new ArrayList<>(Arrays.asList(normalize(CLASSPATH.read("fediaf_2025/records.csv")).split("\n")));
        Files.writeString(records, String.join("\n", change.apply(lines)) + "\n", StandardCharsets.UTF_8);
        return path -> path.equals("fediaf_2025/records.csv") ? Files.readString(records, StandardCharsets.UTF_8) : CLASSPATH.read(path);
    }

    private static UnaryOperator<List<String>> firstRow(UnaryOperator<String[]> change) {
        return lines -> {
            String[] cells = lines.get(1).split(",", -1);
            lines.set(1, String.join(",", change.apply(cells)));
            return lines;
        };
    }

    @ParameterizedTest @ValueSource(strings = {"0", "NaN", "-1", "Infinity", "abc"})
    void zeroNanAndNegativeStandardValuesAreRejected(String value, @TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, firstRow(cells -> { cells[3] = value; return cells; }));
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).isInstanceOf(StandardDataException.class);
    }

    @Test void unknownNutrientFailsLoading(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, firstRow(cells -> { cells[1] = "UNKNOWN"; return cells; }));
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("마스터에 없는 성분");
    }

    @Test void unknownLifeStageFailsLoading(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, firstRow(cells -> { cells[2] = "DOG_SENIOR"; return cells; }));
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("프로필에 없는 생애주기");
    }

    @Test void minAboveMaxFailsLoading(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, firstRow(cells -> { cells[3] = "9000"; return cells; }));
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("min > max");
    }

    @Test void inconvertibleUnitFailsLoading(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, firstRow(cells -> { cells[5] = "IU"; return cells; }));
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("환산할 수 없습니다");
    }

    @Test void missingRecordFailsLoading(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, lines -> { lines.remove(1); return lines; });
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("레코드가 없습니다");
    }

    @Test void duplicateRecordFailsLoading(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, lines -> { lines.add(lines.get(1)); return lines; });
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("중복");
    }

    @Test void verifiedRecordNeedsVerifiedAt(@TempDir Path dir) throws IOException {
        StandardLoader.Source source = modified(dir, firstRow(cells -> { cells[12] = ""; return cells; }));
        assertThatThrownBy(() -> new StandardRegistry(MAPPER, source, false)).hasMessageContaining("verifiedAt이 필요합니다");
    }
}
