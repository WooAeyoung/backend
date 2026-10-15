package kr.co.wooaeyoung.domain.standard;

import kr.co.wooaeyoung.domain.standard.StandardModels.*;

import java.util.*;

/** 영양소 마스터·기준값 레코드 무결성 검사. Python app/standards/loader.py와 같은 규칙이다. */
public final class StandardValidator {
    private StandardValidator() {}

    public static List<String> validateCatalog(NutrientCatalog catalog) {
        List<String> errors = new ArrayList<>();
        List<NutrientMaster> every = new ArrayList<>(catalog.nutrients());
        every.addAll(catalog.profileSpecific());
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (NutrientMaster nutrient : every) {
            if (!ids.add(nutrient.id())) errors.add("영양소 ID 중복: " + nutrient.id());
            if (!orders.add(nutrient.sortOrder())) errors.add("sortOrder 중복: " + nutrient.sortOrder());
            if (nutrient.id() == null || !StandardModels.NUTRIENT_ID.matcher(nutrient.id()).matches())
                errors.add(nutrient.id() + ": ID 형식은 UPPER_SNAKE여야 합니다.");
            if (!StandardModels.UNITS.contains(nutrient.baseUnit())) errors.add(nutrient.id() + ": 허용되지 않은 단위 " + nutrient.baseUnit());
            nutrient.unitConversions().forEach((unit, factor) -> {
                if (!StandardModels.UNITS.contains(unit) || factor == null || factor <= 0)
                    errors.add(nutrient.id() + ": 잘못된 단위 환산 " + unit + "=" + factor);
            });
        }
        for (NutrientMaster nutrient : catalog.nutrients())
            if (!nutrient.profiles().isEmpty()) errors.add(nutrient.id() + ": 공통 마스터 성분에는 profiles를 두지 않습니다.");
        for (NutrientMaster nutrient : catalog.profileSpecific())
            if (nutrient.profiles().isEmpty()) errors.add(nutrient.id() + ": 프로필 전용 성분은 profiles가 필요합니다.");
        catalog.catalogUnits().forEach((id, unit) -> {
            if (!ids.contains(id) || !StandardModels.UNITS.contains(unit)) errors.add("catalogUnits." + id + ": 알 수 없는 성분 또는 단위");
        });
        return errors;
    }

    public static List<String> validateProfile(StandardProfile profile, StandardProfileId expected, NutrientCatalog catalog) {
        List<String> errors = new ArrayList<>();
        if (profile.id() != expected) errors.add("프로필 ID 불일치: " + profile.id());
        if (!Set.of("FEDIAF_2025", "NIAS_V3").contains(profile.model())) errors.add("알 수 없는 계산 모델: " + profile.model());
        Map<String, NutrientMaster> known = UnitConversion.nutrientsFor(catalog, expected);
        for (ProfileNutrient item : profile.nutrients()) {
            NutrientMaster master = known.get(item.id());
            // 기준이 전혀 적용되지 않는 성분(예: FEDIAF 비타민 E, 제품 mg ↔ 원문 IU)은 단위 환산을 요구하지 않는다.
            boolean notApplicable = profile.records().stream().filter(record -> record.nutrientId().equals(item.id()))
                    .allMatch(record -> record.verificationStatus() == VerificationStatus.NOT_APPLICABLE);
            if (master == null) errors.add("분석 성분 " + item.id() + "가 마스터에 없습니다.");
            else if (!StandardModels.UNITS.contains(item.unit())) errors.add("분석 성분 " + item.id() + ": 허용되지 않은 단위 " + item.unit());
            else if (!notApplicable && !UnitConversion.convertible(item.unit(), master.baseUnit(), master))
                errors.add("분석 성분 " + item.id() + ": 단위 " + item.unit() + "를 마스터 단위 " + master.baseUnit() + "로 환산할 수 없습니다.");
        }
        Set<String> stageKeys = new HashSet<>();
        for (LifeStage stage : profile.lifeStages()) {
            if (!stageKeys.add(stage.species() + "/" + stage.id())) errors.add("생애주기 중복: " + stage.id());
            if (!Set.of("DOG", "CAT").contains(stage.species())) errors.add("생애주기 " + stage.id() + ": 종 오류 " + stage.species());
        }
        Set<String> recordStages = new HashSet<>(profile.recordStages());
        Set<String> seen = new HashSet<>();
        for (StandardRecord record : profile.records()) {
            String label = "레코드 " + record.nutrientId() + "/" + record.lifeStage();
            if (record.profile() != expected) errors.add(label + ": profile 불일치");
            if (!seen.add(record.nutrientId() + "/" + record.lifeStage())) errors.add(label + ": 중복");
            NutrientMaster master = known.get(record.nutrientId());
            if (master == null) errors.add(label + ": 마스터에 없는 성분");
            if (!recordStages.contains(record.lifeStage())) errors.add(label + ": 프로필에 없는 생애주기");
            if (blank(record.sourceDocument()) || blank(record.basis())) errors.add(label + ": sourceDocument·basis가 필요합니다.");
            if (!StandardModels.UNITS.contains(record.unit())) errors.add(label + ": 허용되지 않은 단위 " + record.unit());
            boolean hasValue = record.min() != null || record.max() != null;
            if (master != null && hasValue && !UnitConversion.convertible(record.unit(), master.baseUnit(), master))
                errors.add(label + ": 단위 " + record.unit() + "를 마스터 단위 " + master.baseUnit() + "로 환산할 수 없습니다.");
            if (record.min() != null && record.max() != null && record.min() > record.max()) errors.add(label + ": min > max");
            if (record.verificationStatus() == VerificationStatus.VERIFIED && (record.sourceTable() == null || record.verifiedAt() == null))
                errors.add(label + ": VERIFIED 값은 sourceTable·verifiedAt이 필요합니다.");
            if (record.verificationStatus() != VerificationStatus.VERIFIED && record.verifiedAt() != null)
                errors.add(label + ": 검증되지 않은 값에 verifiedAt이 있습니다.");
            if (record.verificationStatus() == VerificationStatus.NOT_APPLICABLE && hasValue)
                errors.add(label + ": NOT_APPLICABLE 레코드에 기준값이 있습니다.");
        }
        for (ProfileNutrient item : profile.nutrients())
            for (String stage : recordStages)
                if (!seen.contains(item.id() + "/" + stage)) errors.add("분석 성분 " + item.id() + "/" + stage + " 레코드가 없습니다.");
        profile.params().forEach((name, param) -> {
            if (param == null || param.verificationStatus() == null || blank(param.basis())) errors.add("매개변수 " + name + ": verificationStatus·basis가 필요합니다.");
            else if (param.verificationStatus() == VerificationStatus.VERIFIED && blank(param.source()))
                errors.add("매개변수 " + name + ": VERIFIED 값은 source가 필요합니다.");
        });
        Double previous = 0.0;
        List<GrowthCurve> curves = new ArrayList<>(profile.growthCurves().values());
        for (int index = 0; index < curves.size(); index++) {
            GrowthCurve curve = curves.get(index);
            if (!Objects.equals(curve.adultWeightKgAbove(), previous)) errors.add("성장곡선 체급 구간이 이어지지 않습니다.");
            if ((index == curves.size() - 1) != (curve.adultWeightKgAtMost() == null)) errors.add("성장곡선은 마지막 체급만 상한이 없어야 합니다.");
            previous = curve.adultWeightKgAtMost();
        }
        return errors;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
