import pytest
from fastapi.testclient import TestClient
from app.engine import classify
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
    assert body["standardVersion"] == "DEMO-2026.1"
    assert len(body["nutrients"]) == 6
    assert body["ratios"]["calciumPhosphorus"]["status"] == "ADEQUATE"

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

