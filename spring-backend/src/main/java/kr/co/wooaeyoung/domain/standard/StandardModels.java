package kr.co.wooaeyoung.domain.standard;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** 기준 프로필 데이터 모델. 모든 컬렉션은 생성 시 복사한 읽기 전용이라 적재 뒤 바꿀 수 없다. */
public final class StandardModels {
    private StandardModels() {}

    public static final List<String> UNITS = List.of("G", "MG", "UG", "IU");
    public static final Pattern NUTRIENT_ID = Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$");

    public enum StandardProfileId {
        FEDIAF_2025("fediaf_2025"), NIAS_V3("nias_v3");

        public static final StandardProfileId DEFAULT = FEDIAF_2025;
        private final String directory;

        StandardProfileId(String directory) { this.directory = directory; }
        public String directory() { return directory; }

        public static Optional<StandardProfileId> parse(String value) {
            if (value == null) return Optional.of(DEFAULT);
            return Arrays.stream(values()).filter(id -> id.name().equals(value)).findFirst();
        }
    }

    public enum VerificationStatus { VERIFIED, UNVERIFIED, NOT_APPLICABLE }

    public record NutrientMaster(String id, String nameKo, String nameEn, String category, String baseUnit, int sortOrder,
                                 boolean prioritySupported, Map<String, Double> unitConversions, List<StandardProfileId> profiles) {
        public NutrientMaster {
            // 1 {키 단위} = 값 {baseUnit}. 예) 비타민 D 1 UG = 40 IU
            unitConversions = readOnly(unitConversions);
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }

    public record NutrientCatalog(List<NutrientMaster> nutrients, List<NutrientMaster> profileSpecific, Map<String, String> catalogUnits) {
        public NutrientCatalog {
            nutrients = List.copyOf(nutrients);
            profileSpecific = profileSpecific == null ? List.of() : List.copyOf(profileSpecific);
            catalogUnits = readOnly(catalogUnits);
        }
    }

    /** 기준값 레코드 하나 = (profile, nutrientId, lifeStage). 빈칸은 null로 보존한다. */
    public record StandardRecord(StandardProfileId profile, String nutrientId, String lifeStage, Double min, Double max, String unit,
                                 String basis, String sourceDocument, String sourceTable, String sourceFootnote,
                                 VerificationStatus verificationStatus, String verifiedBy, LocalDate verifiedAt) {}

    /** 계산 매개변수 하나와 그 근거. value가 null이면 값 미확정. */
    public record Param(Double value, VerificationStatus verificationStatus, String basis, String source) {}

    public record ProfileNutrient(String id, String unit) {}

    public record LifeStage(String id, String species, String recordStage) {}

    public record GrowthCurve(Param a, Param b, double adultWeightKgAbove, Double adultWeightKgAtMost) {}

    public record StandardProfile(StandardProfileId id, String displayName, String publisher, String documentName, String version,
                                  String basis, String sourceText, String model, List<ProfileNutrient> nutrients,
                                  List<LifeStage> lifeStages, Map<String, Param> params, Map<String, GrowthCurve> growthCurves,
                                  List<StandardRecord> records) {
        public StandardProfile {
            nutrients = List.copyOf(nutrients);
            lifeStages = List.copyOf(lifeStages);
            params = readOnly(params);
            growthCurves = readOnly(growthCurves);
            records = records == null ? List.of() : List.copyOf(records);
        }

        public boolean legacy() { return id == StandardProfileId.FEDIAF_2025; }

        public double param(String name) {
            Param param = params.get(name);
            if (param == null || param.value() == null) throw new IllegalStateException(id + " 매개변수 " + name + " 값이 비어 있습니다.");
            return param.value();
        }

        public LifeStage stage(String species, String stageId) {
            return lifeStages.stream().filter(stage -> stage.species().equals(species) && stage.id().equals(stageId)).findFirst().orElseThrow();
        }

        public List<String> recordStages() {
            return lifeStages.stream().map(LifeStage::recordStage).distinct().toList();
        }

        public StandardProfile withRecords(List<StandardRecord> value) {
            return new StandardProfile(id, displayName, publisher, documentName, version, basis, sourceText, model, nutrients,
                    lifeStages, params, growthCurves, value);
        }
    }

    /** 순서를 유지하는 읽기 전용 복사본. */
    static <K, V> Map<K, V> readOnly(Map<K, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
