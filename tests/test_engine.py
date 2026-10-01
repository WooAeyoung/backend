import pytest
from fastapi.testclient import TestClient
from app.engine import classify, life_stage
from app.models import AnalysisRequest
from app.main import app

client = TestClient(app)

def payload(product="feed-balanced-dog", amount=80):
    return {"profile":{"name":"몽이","species":"DOG","weightKg":6.2,"age":{"value":24,"unit":"MONTH"},"completeFeed":True},"items":[{"productId":product,"dailyAmount":amount,"unit":"G"}],"manualItems":[]}

def test_status_boundaries_and_null_upper():
    line = {"minimum":10,"caution":15,"upper":20}
    assert classify(9.99,line) == "DEFICIENT"
    assert classify(10,line) == "ADEQUATE"
    assert classify(15,line) == "CAUTION"
    assert classify(20.01,line) == "EXCESS"
    assert classify(10,{"minimum":10,"caution":None,"upper":None}) == "ADEQUATE_NO_UPPER_LIMIT"

def test_analysis_returns_trace_ratio_and_all_nutrients():
    response = client.post("/api/v1/analyses", json=payload())
    assert response.status_code == 200
    body = response.json()
    assert body["standardVersion"] == "FEDIAF-2025.09"
    assert len(body["nutrients"]) == 6
    assert body["ratios"]["calciumPhosphorus"]["status"] == "ADEQUATE"
    assert next(item for item in body["nutrients"] if item["nutrientId"] == "VITAMIN_E")["status"] == "NO_STANDARD"
    assert next(item for item in body["nutrients"] if item["nutrientId"] == "ZINC")["status"] in {"DEFICIENT", "ADEQUATE_NO_UPPER_LIMIT"}

def test_estimated_feed_is_explicit():
    response = client.post("/api/v1/analyses", json=payload("feed-complete-unknown",100))
    assert response.status_code == 200
    assert response.json()["usesEstimatedFeed"] is True
    assert response.json()["warnings"]

def test_recommendation_never_returns_more_than_requested():
    body = payload(); body["maxItems"] = 2
    response = client.post("/api/v1/recommendations", json=body)
    assert response.status_code == 200
    assert len(response.json()["items"]) <= 2

def test_puppy_requires_adult_size():
    body = payload(); body["profile"]["age"] = {"value":6,"unit":"MONTH"}
    assert client.post("/api/v1/analyses", json=body).status_code == 422

def test_puppy_energy_uses_expected_adult_weight_and_returns_contributions():
    body = payload(); body["profile"].update({"weightKg":6,"age":{"value":6,"unit":"MONTH"},"expectedAdultWeightKg":20})
    response = client.post("/api/v1/analyses", json=body)
    assert response.status_code == 200
    result = response.json()
    assert result["referenceEnergyKcal"] == pytest.approx((254.1 - 135 * (6 / 20)) * 6 ** 0.75)
    assert result["contributions"][0]["name"] == "데일리 밸런스 독"
    assert result["summary"]["noStandard"] == 2
    nutrients = {item["nutrientId"]: item for item in result["nutrients"]}
    kcal = result["referenceEnergyKcal"]
    assert nutrients["CALCIUM"]["minimum"] == pytest.approx(kcal / 1000 * 2000)
    assert nutrients["PHOSPHORUS"]["minimum"] == pytest.approx(kcal / 1000 * 1750)
    assert nutrients["VITAMIN_D"]["minimum"] == pytest.approx(kcal / 1000 * 3.125)

    body["maxItems"] = 3
    recommendation = client.post("/api/v1/recommendations", json=body).json()
    assert len(recommendation["items"]) <= 3
    assert "기준" in recommendation["message"]

def test_large_breed_puppy_uses_early_late_growth_calcium_rule():
    body = payload(); body["profile"].update({"weightKg":5,"age":{"value":5,"unit":"MONTH"},"expectedAdultWeightKg":20})
    result = client.post("/api/v1/analyses", json=body).json()
    calcium = next(item for item in result["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert calcium["minimum"] == pytest.approx(result["referenceEnergyKcal"] / 1000 * 2500)

def test_recommendation_excludes_candidate_that_breaks_calcium_phosphorus_ratio():
    body = payload(amount=1); body["maxItems"] = 3
    result = client.post("/api/v1/recommendations", json=body).json()
    excluded = next(item for item in result["excluded"] if item["productId"] == "supp-calcium")
    assert "칼슘:인 비율" in excluded["reason"]

@pytest.mark.parametrize(("weeks", "expected"), [(13.999, "GROWTH_EARLY"), (14, "GROWTH_LATE")])
def test_fourteen_week_life_stage_boundary(weeks, expected):
    body = payload(); body["profile"].update({"age":{"value":weeks,"unit":"WEEK"},"expectedAdultWeightKg":15})
    assert life_stage(AnalysisRequest.model_validate(body)) == expected

@pytest.mark.parametrize(("adult_weight", "months", "calcium_per_1000"), [(15, 5, 2000), (15.01, 5, 2500), (20, 6, 2000)])
def test_large_breed_weight_and_six_month_calcium_boundaries(adult_weight, months, calcium_per_1000):
    body = payload(); body["profile"].update({"weightKg":5,"age":{"value":months,"unit":"MONTH"},"expectedAdultWeightKg":adult_weight})
    result = client.post("/api/v1/analyses", json=body).json()
    calcium = next(item for item in result["nutrients"] if item["nutrientId"] == "CALCIUM")
    assert calcium["minimum"] == pytest.approx(result["referenceEnergyKcal"] / 1000 * calcium_per_1000)

def test_product_search_uses_prefix_index_and_barcode_hash():
    prefix = client.get("/api/v1/products", params={"query":"칼슘"})
    assert prefix.status_code == 200
    assert [item["id"] for item in prefix.json()["items"]] == ["supp-calcium"]

    barcode = client.get("/api/v1/products", params={"query":"8801000000059"})
    assert barcode.status_code == 200
    assert barcode.json()["items"][0]["id"] == "supp-omega"

def test_product_search_can_filter_type():
    response = client.get("/api/v1/products", params={"query":"데일리", "type":"SUPPLEMENT"})
    assert response.status_code == 200
    assert [item["id"] for item in response.json()["items"]] == ["supp-multi"]

