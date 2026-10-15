"""data/standards의 JSON·CSV를 읽어 검증된 불변 모델로 만든다. 무결성 오류가 있으면 시작 단계에서 실패한다."""
from __future__ import annotations

import csv
import json
from pathlib import Path

from .models import (NUTRIENT_ID_PATTERN, UNITS, NutrientCatalog, NutrientMaster, StandardProfile,
                     StandardProfileId, VerificationStatus)

DATA_DIR = Path(__file__).parents[2] / "data" / "standards"
PROFILE_DIRS = {StandardProfileId.FEDIAF_2025: "fediaf_2025", StandardProfileId.NIAS_V3: "nias_v3"}
MASS_FACTOR = {"UG": 0.001, "MG": 1.0, "G": 1000.0}


class StandardDataError(ValueError):
    pass


def read_records(path: Path) -> list[dict]:
    """CSV 빈 셀은 0이나 ""가 아니라 None으로 읽는다. 숫자 변환은 Pydantic이 한다."""
    with open(path, encoding="utf-8", newline="") as handle:
        return [{key: (value if value.strip() != "" else None) for key, value in row.items()} for row in csv.DictReader(handle)]


def load_catalog(data_dir: Path = DATA_DIR) -> NutrientCatalog:
    catalog = NutrientCatalog.model_validate(json.loads((data_dir / "nutrients.json").read_text(encoding="utf-8")))
    errors = validate_catalog(catalog)
    if errors:
        raise StandardDataError("영양소 마스터 오류:\n- " + "\n- ".join(errors))
    return catalog


def load_profile(profile_id: StandardProfileId, catalog: NutrientCatalog, data_dir: Path = DATA_DIR) -> StandardProfile:
    directory = data_dir / PROFILE_DIRS[profile_id]
    document = json.loads((directory / "profile.json").read_text(encoding="utf-8"))
    document["records"] = read_records(directory / "records.csv")
    profile = StandardProfile.model_validate(document)
    errors = validate_profile(profile, profile_id, catalog)
    if errors:
        raise StandardDataError(f"{profile_id.value} 기준 데이터 오류:\n- " + "\n- ".join(errors))
    return profile


def convertible(source: str, target: str, nutrient: NutrientMaster) -> bool:
    return source == target or (source in MASS_FACTOR and target in MASS_FACTOR) or source in nutrient.unitConversions


def conversion_factor(source: str, target: str, nutrient: NutrientMaster) -> float | None:
    """1 source 단위 = factor target 단위. 환산 근거가 없으면 None."""
    if source == target:
        return 1.0
    if source in MASS_FACTOR and target in MASS_FACTOR:
        return MASS_FACTOR[source] / MASS_FACTOR[target]
    if target == nutrient.baseUnit and source in nutrient.unitConversions:
        return nutrient.unitConversions[source]
    if source == nutrient.baseUnit and target in nutrient.unitConversions:
        return 1 / nutrient.unitConversions[target]
    return None


def nutrients_for(catalog: NutrientCatalog, profile_id: StandardProfileId) -> dict[str, NutrientMaster]:
    """공통 44개 + 해당 프로필 전용 성분."""
    result = {nutrient.id: nutrient for nutrient in catalog.nutrients}
    result.update({nutrient.id: nutrient for nutrient in catalog.profileSpecific if profile_id in nutrient.profiles})
    return result


def validate_catalog(catalog: NutrientCatalog) -> list[str]:
    errors = []
    every = list(catalog.nutrients) + list(catalog.profileSpecific)
    ids = [nutrient.id for nutrient in every]
    if len(ids) != len(set(ids)):
        errors.append("영양소 ID 중복")
    orders = [nutrient.sortOrder for nutrient in every]
    if len(orders) != len(set(orders)):
        errors.append("sortOrder 중복")
    for nutrient in every:
        if not NUTRIENT_ID_PATTERN.match(nutrient.id):
            errors.append(f"{nutrient.id}: ID 형식은 UPPER_SNAKE여야 합니다.")
        if nutrient.baseUnit not in UNITS:
            errors.append(f"{nutrient.id}: 허용되지 않은 단위 {nutrient.baseUnit}")
        for unit, factor in nutrient.unitConversions.items():
            if unit not in UNITS or factor <= 0:
                errors.append(f"{nutrient.id}: 잘못된 단위 환산 {unit}={factor}")
    for nutrient in catalog.nutrients:
        if nutrient.profiles:
            errors.append(f"{nutrient.id}: 공통 마스터 성분에는 profiles를 두지 않습니다.")
    for nutrient in catalog.profileSpecific:
        if not nutrient.profiles:
            errors.append(f"{nutrient.id}: 프로필 전용 성분은 profiles가 필요합니다.")
    for nutrient_id, unit in catalog.catalogUnits.items():
        if nutrient_id not in ids or unit not in UNITS:
            errors.append(f"catalogUnits.{nutrient_id}: 알 수 없는 성분 또는 단위")
    return errors


def validate_profile(profile: StandardProfile, expected: StandardProfileId, catalog: NutrientCatalog) -> list[str]:
    errors = []
    if profile.id != expected:
        errors.append(f"프로필 ID 불일치: {profile.id.value}")
    known = nutrients_for(catalog, expected)
    for item in profile.nutrients:
        master = known.get(item.id)
        # 기준이 전혀 적용되지 않는 성분(예: FEDIAF 비타민 E, 제품 mg ↔ 원문 IU)은 단위 환산을 요구하지 않는다.
        not_applicable = all(record.verificationStatus == VerificationStatus.NOT_APPLICABLE
                             for record in profile.records if record.nutrientId == item.id)
        if master is None:
            errors.append(f"분석 성분 {item.id}가 마스터에 없습니다.")
        elif item.unit not in UNITS:
            errors.append(f"분석 성분 {item.id}: 허용되지 않은 단위 {item.unit}")
        elif not not_applicable and not convertible(item.unit, master.baseUnit, master):
            errors.append(f"분석 성분 {item.id}: 단위 {item.unit}를 마스터 단위 {master.baseUnit}로 환산할 수 없습니다.")
    stage_keys = [(stage.species, stage.id) for stage in profile.lifeStages]
    if len(stage_keys) != len(set(stage_keys)):
        errors.append("생애주기 중복")
    for stage in profile.lifeStages:
        if stage.species not in {"DOG", "CAT"}:
            errors.append(f"생애주기 {stage.id}: 종 오류 {stage.species}")
    record_stages = set(profile.record_stages())
    seen = set()
    for record in profile.records:
        key = (record.nutrientId, record.lifeStage)
        label = f"레코드 {record.nutrientId}/{record.lifeStage}"
        if record.profile != expected:
            errors.append(f"{label}: profile 불일치")
        if key in seen:
            errors.append(f"{label}: 중복")
        seen.add(key)
        master = known.get(record.nutrientId)
        if master is None:
            errors.append(f"{label}: 마스터에 없는 성분")
        if record.lifeStage not in record_stages:
            errors.append(f"{label}: 프로필에 없는 생애주기")
        if not record.sourceDocument or not record.basis:
            errors.append(f"{label}: sourceDocument·basis가 필요합니다.")
        if record.unit not in UNITS:
            errors.append(f"{label}: 허용되지 않은 단위 {record.unit}")
        has_value = record.min is not None or record.max is not None
        if master is not None and has_value and not convertible(record.unit, master.baseUnit, master):
            errors.append(f"{label}: 단위 {record.unit}를 마스터 단위 {master.baseUnit}로 환산할 수 없습니다.")
        if record.min is not None and record.max is not None and record.min > record.max:
            errors.append(f"{label}: min > max")
        if record.verificationStatus == VerificationStatus.VERIFIED and (record.sourceTable is None or record.verifiedAt is None):
            errors.append(f"{label}: VERIFIED 값은 sourceTable·verifiedAt이 필요합니다.")
        if record.verificationStatus != VerificationStatus.VERIFIED and record.verifiedAt is not None:
            errors.append(f"{label}: 검증되지 않은 값에 verifiedAt이 있습니다.")
        if record.verificationStatus == VerificationStatus.NOT_APPLICABLE and has_value:
            errors.append(f"{label}: NOT_APPLICABLE 레코드에 기준값이 있습니다.")
    for item in profile.nutrients:
        for stage in record_stages:
            if (item.id, stage) not in seen:
                errors.append(f"분석 성분 {item.id}/{stage} 레코드가 없습니다.")
    for name, param in profile.params.items():
        if param.verificationStatus == VerificationStatus.VERIFIED and not param.source:
            errors.append(f"매개변수 {name}: VERIFIED 값은 source가 필요합니다.")
    previous = 0.0
    curves = list(profile.growthCurves.values())
    for index, curve in enumerate(curves):
        if curve.adultWeightKgAbove != previous:
            errors.append("성장곡선 체급 구간이 이어지지 않습니다.")
        if (index == len(curves) - 1) != (curve.adultWeightKgAtMost is None):
            errors.append("성장곡선은 마지막 체급만 상한이 없어야 합니다.")
        previous = curve.adultWeightKgAtMost
    return errors
