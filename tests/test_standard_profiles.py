"""기준 프로필 분리·요청/응답 계약·미검증 기준값 차단."""
import copy
import csv
import shutil

import pytest
from fastapi.testclient import TestClient

import app.engine as engine
from app.main import app
from app.standards.loader import DATA_DIR, load_catalog, load_profile
from app.standards.models import StandardProfileId

client = TestClient(app)


def body(profile=None, species="DOG", weight=10, age=36, unit="MONTH", items=None, **extra):
    request = {"profile": {"name": "테스트", "species": species, "weightKg": weight, "age": {"value": age, "unit": unit}, "completeFeed": True, **extra},
               "items": items if items is not None else [{"productId": "feed-balanced-dog", "dailyAmount": 150, "unit": "G"}]}
    if profile:
        request["standardProfile"] = profile
    return request


def stable(result):
    return {key: value for key, value in result.items() if key != "traceId"}


@pytest.fixture(autouse=True)
def default_gate(monkeypatch):
    monkeypatch.delenv("ALLOW_UNVERIFIED_STANDARDS", raising=False)


def test_unspecified_profile_is_fediaf_and_unchanged():
    implicit = client.post("/api/v1/analyses", json=body()).json()
    explicit = client.post("/api/v1/analyses", json=body("FEDIAF_2025")).json()
    assert implicit["standard"]["id"] == "FEDIAF_2025"
    assert stable(implicit) == stable(explicit)


@pytest.mark.parametrize("path", ["/api/v1/analyses", "/api/v1/recommendations"])
def test_unknown_profile_is_422(path):
    assert client.post(path, json=body("NIAS_2024")).status_code == 422


@pytest.mark.parametrize("path", ["/health", "/api/v1/nutrients"])
def test_unknown_profile_query_is_422(path):
    assert client.get(path, params={"standardProfile": "UNKNOWN"}).status_code == 422


@pytest.mark.parametrize("path", ["/api/v1/analyses", "/api/v1/recommendations"])
def test_same_input_runs_independently_in_either_order(path):
    first_fediaf = stable(client.post(path, json=body("FEDIAF_2025")).json())
    first_nias = stable(client.post(path, json=body("NIAS_V3")).json())
    second_nias = stable(client.post(path, json=body("NIAS_V3")).json())
    second_fediaf = stable(client.post(path, json=body("FEDIAF_2025")).json())
    assert first_fediaf == second_fediaf
    assert first_nias == second_nias
    assert first_fediaf["standard"]["id"] == "FEDIAF_2025" and first_nias["standard"]["id"] == "NIAS_V3"


def test_trace_ids_differ_by_profile():
    fediaf = client.post("/api/v1/analyses", json=body("FEDIAF_2025")).json()["traceId"]
    nias = client.post("/api/v1/analyses", json=body("NIAS_V3")).json()["traceId"]
    assert fediaf.split("-")[0] != nias.split("-")[0]


def test_standard_and_reference_blocks_show_version_and_source():
    fediaf = client.post("/api/v1/analyses", json=body("FEDIAF_2025")).json()
    assert fediaf["standard"]["version"] == "FEDIAF-2025.09"
    assert fediaf["standard"]["documentName"] == "FEDIAF Nutritional Guidelines 2025"
    assert fediaf["standard"]["verification"]["unverified"] == 0
    calcium = next(item for item in fediaf["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert calcium["reference"] == {"sourceDocument": "FEDIAF Nutritional Guidelines 2025", "sourceTable": "Table III-3b",
                                    "sourceFootnote": None, "unit": "MG", "basis": "per_1000kcal_ME",
                                    "verificationStatus": "VERIFIED", "verifiedAt": "2026-10-01", "appliedInJudgement": True}

    nias = client.post("/api/v1/analyses", json=body("NIAS_V3")).json()
    assert nias["standard"]["version"] == "NIAS-v3"
    assert nias["standard"]["publisher"] == "농촌진흥청 국립축산과학원"
    calcium = next(item for item in nias["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert calcium["reference"]["sourceTable"] == "표 2-17d"
    assert calcium["reference"]["verificationStatus"] == "UNVERIFIED"
    assert calcium["unit"] == "G"


def test_large_breed_fediaf_reference_names_footnote():
    request = body(weight=12, age=5, expectedAdultWeightKg=30)
    calcium = next(item for item in client.post("/api/v1/analyses", json=request).json()["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert "footnote b" in calcium["reference"]["sourceFootnote"]


def test_nias_analysis_runs_with_unverified_profile_and_blocks_judgement():
    response = client.post("/api/v1/analyses", json=body("NIAS_V3"))
    assert response.status_code == 200
    result = response.json()
    assert len(result["nutrients"]) == 18
    assert {item["status"] for item in result["nutrients"]} == {"UNVERIFIED_STANDARD"}
    assert result["summary"]["unverifiedStandard"] == 18
    for item in result["nutrients"]:
        # 빈칸은 응답에서도 0이 아니라 null이다.
        assert item["minimum"] is None and item["caution"] is None and item["upper"] is None
    assert result["ratios"]["calciumPhosphorus"]["status"] == "UNVERIFIED_STANDARD"
    assert any("미검증 프로필" in warning for warning in result["warnings"])
    assert result["standard"]["allowUnverifiedStandards"] is False
    assert "energyKDogAdult" in result["standard"]["verification"]["unverifiedParameters"]


def test_nias_uses_profile_parameters_and_converts_catalog_units():
    result = client.post("/api/v1/analyses", json=body("NIAS_V3")).json()
    assert result["lifeStage"] == "DOG_ADULT"
    assert result["energyFactor"] == 110
    assert result["referenceEnergyKcal"] == pytest.approx(110 * 10 ** 0.75)
    nutrients = {item["nutrientId"]: item for item in result["nutrients"]}
    # 데모 사료 칼슘 900 mg/100 g × 150 g = 1.35 g, 비타민 D 6 µg × 1.5 × 40 = 360 IU
    assert nutrients["CALCIUM"]["total"] == pytest.approx(1.35)
    assert nutrients["VITAMIN_D"]["total"] == pytest.approx(360)
    assert nutrients["VITAMIN_E"]["total"] == 0
    assert any("OMEGA3" in warning for warning in result["warnings"])
    assert any("VITAMIN_E(MG)" in warning for warning in result["warnings"])


def test_nias_life_stages_and_growth():
    puppy = client.post("/api/v1/analyses", json=body("NIAS_V3", weight=3, age=12, unit="WEEK", adultSize="S")).json()
    assert puppy["lifeStage"] == "DOG_GROWTH_EARLY"
    assert puppy["growth"]["sizeClass"] == "S"
    assert puppy["energyFactor"] == pytest.approx(254.1 - 1.35 * puppy["growth"]["percentOfAdult"])
    kitten = client.post("/api/v1/analyses", json=body("NIAS_V3", species="CAT", weight=1.5, age=3,
                                                      items=[{"productId": "feed-balanced-cat", "dailyAmount": 40, "unit": "G"}])).json()
    assert kitten["lifeStage"] == "CAT_GROWTH_1"
    assert kitten["energyFactor"] == 225


def test_nias_puppy_needs_size_class_but_not_expected_adult_weight():
    response = client.post("/api/v1/analyses", json=body("NIAS_V3", weight=3, age=12, unit="WEEK"))
    assert response.status_code == 422
    assert "adultSize" in response.json()["detail"]["message"]


def test_nutrients_endpoint_is_profile_specific():
    fediaf = client.get("/api/v1/nutrients").json()
    assert [item["id"] for item in fediaf["items"]] == ["CALCIUM", "PHOSPHORUS", "VITAMIN_D", "VITAMIN_E", "OMEGA3", "ZINC"]
    nias = client.get("/api/v1/nutrients", params={"standardProfile": "NIAS_V3"}).json()
    assert len(nias["items"]) == 18 and nias["standard"]["id"] == "NIAS_V3"
    master = client.get("/api/v1/nutrients", params={"standardProfile": "NIAS_V3", "includeAll": True}).json()
    assert len(master["items"]) == 44
    assert sum(item["analyzed"] for item in master["items"]) == 18


def test_health_reports_profile():
    assert client.get("/health").json()["standardVersion"] == "FEDIAF-2025.09"
    nias = client.get("/health", params={"standardProfile": "NIAS_V3"}).json()
    assert nias["standardVersion"] == "NIAS-v3"
    assert nias["standard"]["verification"]["verified"] == 0


@pytest.fixture
def nias_with_test_value(tmp_path, monkeypatch):
    """미검증 차단을 확인하려고 NIAS 칼슘 성견 레코드 하나에만 테스트용 임의값을 넣는다(실제 기준값 아님)."""
    target = tmp_path / "standards"
    shutil.copytree(DATA_DIR, target)
    path = target / "nias_v3" / "records.csv"
    with open(path, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        if row["nutrientId"] == "CALCIUM" and row["lifeStage"] == "DOG_ADULT":
            row.update({"min": "0.5", "max": "50"})
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    profile = load_profile(StandardProfileId.NIAS_V3, load_catalog(target), target)
    original = engine.get_profile
    monkeypatch.setattr(engine, "get_profile", lambda profile_id=None: profile if profile_id == StandardProfileId.NIAS_V3 else original(profile_id))
    return profile


def test_unverified_values_are_not_used_in_judgement_or_recommendation(nias_with_test_value):
    request = body("NIAS_V3", items=[{"productId": "feed-balanced-dog", "dailyAmount": 20, "unit": "G"}])
    calcium = next(item for item in client.post("/api/v1/analyses", json=request).json()["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert calcium["status"] == "UNVERIFIED_STANDARD"
    assert calcium["minimum"] is None
    assert calcium["reference"]["appliedInJudgement"] is False
    request["maxItems"] = 3
    recommended = client.post("/api/v1/recommendations", json=request).json()["items"]
    assert all(item["fixedNutrients"] == 0 for item in recommended)


def test_allow_flag_applies_unverified_values_and_is_reported(nias_with_test_value, monkeypatch):
    monkeypatch.setenv("ALLOW_UNVERIFIED_STANDARDS", "true")
    request = body("NIAS_V3", items=[{"productId": "feed-balanced-dog", "dailyAmount": 20, "unit": "G"}])
    result = client.post("/api/v1/analyses", json=request).json()
    calcium = next(item for item in result["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert calcium["status"] == "DEFICIENT"
    assert calcium["minimum"] == pytest.approx(result["referenceEnergyKcal"] / 1000 * 0.5)
    assert calcium["reference"]["appliedInJudgement"] is True
    assert result["standard"]["allowUnverifiedStandards"] is True
    assert any("ALLOW_UNVERIFIED_STANDARDS" in warning for warning in result["warnings"])
    # 값이 비어 있는 다른 성분은 플래그가 켜져도 기준 없음이다(null이 0이 되지 않는다).
    assert next(item for item in result["nutrients"] if item["nutrientId"] == "ZINC")["status"] == "NO_STANDARD"
    request["maxItems"] = 3
    recommended = client.post("/api/v1/recommendations", json=request).json()["items"]
    assert any(item["productId"] == "supp-calcium" and item["fixedNutrients"] == 1 for item in recommended)


def test_profiles_do_not_share_mutable_state():
    fediaf = engine.get_profile(StandardProfileId.FEDIAF_2025)
    with pytest.raises(TypeError):
        fediaf.params["energyKDogAdult"] = None
    with pytest.raises(Exception):
        fediaf.version = "changed"
    snapshot = copy.deepcopy(fediaf.model_dump())
    client.post("/api/v1/analyses", json=body("NIAS_V3"))
    client.post("/api/v1/recommendations", json=body("FEDIAF_2025"))
    assert fediaf.model_dump() == snapshot
