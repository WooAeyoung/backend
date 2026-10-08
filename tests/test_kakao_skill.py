from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_kakao_skill_returns_open_builder_response():
    response = client.post(
        "/api/v1/kakao/skill",
        json={"userRequest": {"utterance": "도움말"}},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "2.0"
    assert "GitHub 연동" in body["template"]["outputs"][0]["simpleText"]["text"]


def test_kakao_skill_handles_empty_payload():
    response = client.post("/api/v1/kakao/skill", json={})

    assert response.status_code == 200
    assert response.json()["template"]["outputs"][0]["simpleText"]["text"]
