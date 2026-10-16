package kr.co.wooaeyoung.standards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.*;

/**
 * 프로필별 기준값 파일(classpath:standards/{profile}/nutrients.json). 시작 시 한 번 읽고 무결성을 검사한다.
 * 마스터에 없는 프로필 전용 성분(FEDIAF의 OMEGA3)은 name을 직접 가진다.
 */
public record ProfileNutrients(
        @NotNull StandardProfile profile,
        @NotBlank String basis,
        @NotBlank String sourceDocument,
        @NotEmpty List<@Valid @NotNull Nutrient> nutrients) {

    public ProfileNutrients { nutrients = nutrients == null ? null : List.copyOf(nutrients); }

    public record Nutrient(
            @NotBlank String key,
            String name,
            @NotBlank String unit,
            @NotNull Boolean implemented,
            String note,
            @NotNull Map<String, @Valid @NotNull StandardValue> minimum,
            @NotNull Map<String, @Valid @NotNull StandardValue> maximum,
            @NotNull Map<String, @Valid @NotNull StandardValue> caution) {
        public Nutrient {
            minimum = minimum == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(minimum));
            maximum = maximum == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(maximum));
            caution = caution == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(caution));
        }
    }

    /** 생애주기별로 함께 쓰는 열: 최소 열, 최대 열, 예외 주의값 열(종). */
    public record StageColumns(String minimum, String maximum, String caution) {}

    public static ProfileNutrients load(StandardParametersLoader loader, StandardProfile profile) {
        ProfileNutrients file = loader.load("standards/" + profile.directory() + "/nutrients.json", ProfileNutrients.class);
        if (file.profile() != profile) throw new IllegalStateException(profile.directory() + "/nutrients.json의 profile이 " + profile + "가 아닙니다: " + file.profile());
        return file;
    }

    /**
     * 시작 시 무결성 검사. 위반하면 IllegalStateException으로 애플리케이션 시작이 실패한다.
     * unitMustMatchMaster: NIAS는 레코드 단위 = 마스터 기준 단위. FEDIAF는 기존 응답 단위(mg·µg)를 유지하므로 프로필 성분 단위와만 맞춘다.
     */
    public void check(NutrientMaster master, Set<String> requiredMinimum, Set<String> requiredMaximum, Set<String> requiredCaution,
                      List<StageColumns> stages, boolean unitMustMatchMaster) {
        List<String> errors = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Nutrient nutrient : nutrients) {
            String key = nutrient.key();
            if (!keys.add(key)) errors.add("중복 키: " + key);
            Optional<NutrientMaster.Entry> entry = master.find(key);
            if (entry.isEmpty() && (nutrient.name() == null || nutrient.name().isBlank())) errors.add("마스터에 없는 성분은 name이 필요: " + key);
            if (!NutrientMaster.ALLOWED_UNITS.contains(nutrient.unit())) errors.add("허용하지 않는 단위: " + key + "=" + nutrient.unit());
            if (unitMustMatchMaster && entry.isPresent() && !entry.get().baseUnit().equals(nutrient.unit()))
                errors.add("마스터 기준 단위와 다름: " + key + " " + nutrient.unit() + " ≠ " + entry.get().baseUnit());
            if (!nutrient.minimum().keySet().containsAll(requiredMinimum)) errors.add("최소 열 누락: " + key);
            if (!nutrient.maximum().keySet().containsAll(requiredMaximum)) errors.add("최대 열 누락: " + key);
            if (!nutrient.caution().keySet().containsAll(requiredCaution)) errors.add("주의값 열 누락: " + key);
            Map<String, StandardValue> all = new LinkedHashMap<>();
            nutrient.minimum().forEach((column, value) -> all.put("minimum." + column, value));
            nutrient.maximum().forEach((column, value) -> all.put("maximum." + column, value));
            nutrient.caution().forEach((column, value) -> all.put("caution." + column, value));
            all.forEach((column, value) -> {
                String where = key + "." + column;
                if (!value.unit().equals(nutrient.unit())) errors.add("레코드 단위가 성분 단위와 다름: " + where);
                if (!value.basis().equals(basis)) errors.add("basis 불일치: " + where);
                if ((value.value() == null) != (value.verificationStatus() == VerificationStatus.NOT_APPLICABLE))
                    errors.add("빈칸은 NOT_APPLICABLE, 값은 VERIFIED/PENDING이어야 함: " + where);
                if (StandardValue.TABLE_UNCONFIRMED.equals(value.table()) && value.verificationStatus() != VerificationStatus.PENDING_VERIFICATION)
                    errors.add("UNCONFIRMED 표는 PENDING_VERIFICATION이어야 함: " + where);
                if (value.verificationStatus() == VerificationStatus.VERIFIED && value.verifiedAt() == null) errors.add("VERIFIED인데 verifiedAt 없음: " + where);
            });
            for (StageColumns stage : stages) {
                Double min = valueOf(nutrient.minimum(), stage.minimum()), max = valueOf(nutrient.maximum(), stage.maximum()), caution = valueOf(nutrient.caution(), stage.caution());
                if (min != null && max != null && min > max) errors.add("최소 > 최대: " + key + " " + stage);
                if (min != null && caution != null && min > caution) errors.add("최소 > 주의: " + key + " " + stage);
                if (caution != null && max != null && caution > max) errors.add("주의 > 최대: " + key + " " + stage);
            }
        }
        if (!errors.isEmpty()) throw new IllegalStateException(profile + " 기준값 무결성 오류: " + String.join(", ", errors));
    }

    /** 분석 대상(implemented) 성분의 응답 메타데이터. 파일 순서를 따른다. */
    public List<NutrientMeta> analysisNutrients(NutrientMaster master) {
        return nutrients.stream().filter(Nutrient::implemented)
                .map(nutrient -> new NutrientMeta(nutrient.key(), master.find(nutrient.key()).map(NutrientMaster.Entry::id).orElse(null),
                        name(nutrient, master), NutrientMeta.responseUnit(nutrient.unit())))
                .toList();
    }

    public Nutrient nutrient(String key) {
        return nutrients.stream().filter(nutrient -> nutrient.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException(profile + "에 없는 성분: " + key));
    }

    /** 전체 성분 목록. 빈칸은 value=null 그대로 응답한다. */
    public List<Map<String, Object>> catalogView(NutrientMaster master) {
        return nutrients.stream().map(nutrient -> {
            Optional<NutrientMaster.Entry> entry = master.find(nutrient.key());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", nutrient.key());
            item.put("nutrientNo", entry.map(NutrientMaster.Entry::id).orElse(null));
            item.put("name", name(nutrient, master));
            item.put("category", entry.map(NutrientMaster.Entry::category).orElse(null));
            item.put("unit", NutrientMeta.responseUnit(nutrient.unit()));
            item.put("standardUnit", nutrient.unit());
            item.put("masterBaseUnit", entry.map(NutrientMaster.Entry::baseUnit).orElse(null));
            item.put("implemented", nutrient.implemented());
            item.put("note", nutrient.note());
            item.put("minimum", nutrient.minimum());
            item.put("maximum", nutrient.maximum());
            item.put("caution", nutrient.caution());
            return Collections.unmodifiableMap(item);
        }).toList();
    }

    private static String name(Nutrient nutrient, NutrientMaster master) {
        return nutrient.name() != null ? nutrient.name() : master.find(nutrient.key()).map(NutrientMaster.Entry::name).orElseThrow();
    }

    private static Double valueOf(Map<String, StandardValue> values, String column) {
        StandardValue value = column == null ? null : values.get(column);
        return value == null ? null : value.value();
    }
}
