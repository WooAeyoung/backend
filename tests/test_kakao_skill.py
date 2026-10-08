from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_kakao_skill_returns_open_builder_response():
    response = client.post(
        "/api/v1/kakao/skill",
        json={"userRequest": {"utterance": "/도움말"}},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "2.0"
    assert "/상태" in body["template"]["outputs"][0]["simpleText"]["text"]
    assert len(body["template"]["quickReplies"]) == 6


def test_kakao_skill_handles_empty_payload():
    response = client.post("/api/v1/kakao/skill", json={})

    assert response.status_code == 200
    assert response.json()["template"]["outputs"][0]["simpleText"]["text"]


def test_kakao_skill_accepts_commands_without_slash():
    response = client.post(
        "/api/v1/kakao/skill",
        json={"userRequest": {"utterance": "  상태  "}},
    )

    assert response.status_code == 200
    assert "정상적으로 연결" in response.json()["template"]["outputs"][0]["simpleText"]["text"]


def test_kakao_skill_explains_unknown_commands():
    response = client.post(
        "/api/v1/kakao/skill",
        json={"userRequest": {"utterance": "안녕"}},
    )

    assert response.status_code == 200
    body = response.json()["template"]
    assert "알아듣지 못했어요" in body["outputs"][0]["simpleText"]["text"]
    assert body["quickReplies"][0]["messageText"] == "/도움말"


def test_kakao_write_command_requires_allowlist():
    response = client.post(
        "/api/v1/kakao/skill",
        json={
            "userRequest": {
                "utterance": "/배포 frontend",
                "user": {"id": "untrusted-user"},
            }
        },
    )

    assert response.status_code == 200
    text = response.json()["template"]["outputs"][0]["simpleText"]["text"]
    assert "실행 권한이 없습니다" in text


def test_kakao_returns_user_id():
    response = client.post(
        "/api/v1/kakao/skill",
        json={"userRequest": {"utterance": "/내아이디", "user": {"id": "user-123"}}},
    )

    assert response.status_code == 200
    text = response.json()["template"]["outputs"][0]["simpleText"]["text"]
    assert "user-123" in text
