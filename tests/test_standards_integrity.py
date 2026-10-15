"""영양소 마스터·기준값 레코드 무결성과 빈칸(null) 보존."""
import csv
import shutil

import pytest
from pydantic import ValidationError

from app.standards.loader import DATA_DIR, StandardDataError, convertible, load_catalog, load_profile, nutrients_for, read_records
from app.standards.models import NUTRIENT_ID_PATTERN, UNITS, StandardProfileId, VerificationStatus
from app.standards.registry import NUTRIENT_CATALOG, PROFILES

PRIORITY = {"PROTEIN", "FAT", "LINOLEIC_ACID", "ALPHA_LINOLENIC_ACID", "EPA_DHA", "CALCIUM", "PHOSPHORUS", "POTASSIUM",
            "SODIUM", "MAGNESIUM", "IRON", "COPPER", "ZINC", "SELENIUM", "VITAMIN_A", "VITAMIN_D", "VITAMIN_E", "TAURINE"}
LEGACY_IDS = ["CALCIUM", "PHOSPHORUS", "VITAMIN_D", "VITAMIN_E", "OMEGA3", "ZINC"]


def test_master_has_44_unique_nutrients_with_valid_ids_and_units():
    nutrients = NUTRIENT_CATALOG.nutrients
    ids = [nutrient.id for nutrient in nutrients]
    assert len(nutrients) == 44
    assert len(set(ids)) == 44
    assert all(NUTRIENT_ID_PATTERN.match(nutrient_id) for nutrient_id in ids)
    assert all(nutrient.baseUnit in UNITS for nutrient in nutrients)
    assert sorted(nutrient.sortOrder for nutrient in nutrients) == list(range(1, 45))


def test_exactly_18_priority_nutrients():
    assert {nutrient.id for nutrient in NUTRIENT_CATALOG.nutrients if nutrient.prioritySupported} == PRIORITY


def test_fediaf_omega3_is_profile_specific_and_not_mapped_to_nias_fatty_acids():
    assert "OMEGA3" not in {nutrient.id for nutrient in NUTRIENT_CATALOG.nutrients}
    assert "OMEGA3" in nutrients_for(NUTRIENT_CATALOG, StandardProfileId.FEDIAF_2025)
    assert "OMEGA3" not in nutrients_for(NUTRIENT_CATALOG, StandardProfileId.NIAS_V3)
    assert "OMEGA3" not in {item.id for item in PROFILES[StandardProfileId.NIAS_V3].nutrients}


def test_legacy_fediaf_ids_are_kept():
    assert [item.id for item in PROFILES[StandardProfileId.FEDIAF_2025].nutrients] == LEGACY_IDS


@pytest.mark.parametrize("profile_id", list(StandardProfileId))
def test_records_reference_master_and_profile_stages_with_sources(profile_id):
    profile = PROFILES[profile_id]
    known = nutrients_for(NUTRIENT_CATALOG, profile_id)
    stages = set(profile.record_stages())
    for record in profile.records:
        assert record.nutrientId in known
        assert record.lifeStage in stages
        assert record.sourceDocument and record.basis
        master = known[record.nutrientId]
        if record.min is not None or record.max is not None:
            assert convertible(record.unit, master.baseUnit, master)
        if record.min is not None and record.max is not None:
            assert record.min <= record.max


def test_nias_has_every_master_nutrient_for_every_stage_and_no_values():
    profile = PROFILES[StandardProfileId.NIAS_V3]
    assert len(profile.records) == 44 * len(profile.record_stages())
    for record in profile.records:
        # 사용자가 원문 확인 값을 주기 전까지 수치는 비워 두고 미검증으로 둔다.
        assert record.min is None and record.max is None
        assert record.verificationStatus == VerificationStatus.UNVERIFIED
        assert record.sourceTable is not None


def test_fediaf_records_are_verified_or_not_applicable():
    for record in PROFILES[StandardProfileId.FEDIAF_2025].records:
        assert record.verificationStatus in {VerificationStatus.VERIFIED, VerificationStatus.NOT_APPLICABLE}
        if record.verificationStatus == VerificationStatus.VERIFIED:
            assert record.sourceTable and record.verifiedAt


def test_blank_cells_are_read_as_none_not_zero():
    rows = read_records(DATA_DIR / "fediaf_2025" / "records.csv")
    zinc = next(row for row in rows if row["nutrientId"] == "ZINC" and row["lifeStage"] == "DOG_ADULT")
    assert zinc["max"] is None
    record = PROFILES[StandardProfileId.FEDIAF_2025].record("ZINC", "DOG_ADULT")
    assert record.max is None
    assert record.model_dump()["max"] is None


@pytest.fixture
def data_copy(tmp_path):
    target = tmp_path / "standards"
    shutil.copytree(DATA_DIR, target)
    return target


def _rewrite(path, change):
    with open(path, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    change(rows)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


@pytest.mark.parametrize("value", ["0", "nan", "-1", "inf"])
def test_zero_nan_and_negative_standard_values_are_rejected(data_copy, value):
    _rewrite(data_copy / "fediaf_2025" / "records.csv", lambda rows: rows[0].update({"min": value}))
    with pytest.raises(ValidationError):
        load_profile(StandardProfileId.FEDIAF_2025, load_catalog(data_copy), data_copy)


@pytest.mark.parametrize(("change", "message"), [
    (lambda rows: rows[0].update({"nutrientId": "UNKNOWN"}), "마스터에 없는 성분"),
    (lambda rows: rows[0].update({"lifeStage": "DOG_SENIOR"}), "프로필에 없는 생애주기"),
    (lambda rows: rows[0].update({"min": "9000"}), "min > max"),
    (lambda rows: rows[0].update({"unit": "IU"}), "환산할 수 없습니다"),
    (lambda rows: rows.pop(0), "레코드가 없습니다"),
    (lambda rows: rows.append(dict(rows[0])), "중복"),
    (lambda rows: rows[0].update({"verifiedAt": ""}), "verifiedAt이 필요합니다"),
])
def test_record_integrity_errors_fail_loading(data_copy, change, message):
    _rewrite(data_copy / "fediaf_2025" / "records.csv", change)
    with pytest.raises(StandardDataError, match=message):
        load_profile(StandardProfileId.FEDIAF_2025, load_catalog(data_copy), data_copy)
