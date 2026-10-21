from fastapi.testclient import TestClient

from app.main import app
from app.vets import distance_km, nearest_vets

client = TestClient(app)


def test_nearest_vets_are_sorted_and_include_map_links():
    response = client.get("/api/v1/vets/nearest", params={"lat": 37.4981, "lon": 127.0276, "limit": 2})
    assert response.status_code == 200
    body = response.json()
    assert len(body["clinics"]) == 2
    assert body["clinics"][0]["id"] == "vet-seoul-gangnam-01"
    assert body["clinics"][0]["distanceKm"] == 0.0
    assert body["clinics"][0]["mapUrl"].startswith("https://www.google.com/maps/search/")


def test_distance_is_zero_for_same_point_and_limit_is_validated():
    assert distance_km(0, 0, 0, 0) == 0
    assert len(nearest_vets(37.5, 127.0, 1)) == 1
    assert client.get("/api/v1/vets/nearest", params={"lat": 91, "lon": 127}).status_code == 422
