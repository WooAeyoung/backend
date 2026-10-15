"""리팩터링 전 FEDIAF 2025 응답을 골든 파일로 저장한다.

    python -m tests.golden.generate_fediaf_golden

이미 파일이 있으면 덮어쓰지 않는다. 의도적으로 기준을 바꿀 때만 --force를 쓴다.
"""
import json
import sys
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app
from tests.golden.fediaf_cases import CASES, GET_CASES, VOLATILE_KEYS

OUTPUT = Path(__file__).with_name("fediaf_2025.json")


def strip_volatile(value):
    if isinstance(value, dict):
        return {key: strip_volatile(item) for key, item in value.items() if key not in VOLATILE_KEYS}
    if isinstance(value, list):
        return [strip_volatile(item) for item in value]
    return value


def collect() -> dict:
    client = TestClient(app)
    result = {}
    for name, path, body in CASES:
        response = client.post(path, json=body)
        result[name] = {"status": response.status_code, "body": strip_volatile(response.json())}
    for name, path in GET_CASES:
        response = client.get(path)
        result[name] = {"status": response.status_code, "body": strip_volatile(response.json())}
    return result


def main() -> None:
    if OUTPUT.exists() and "--force" not in sys.argv:
        raise SystemExit(f"{OUTPUT.name}이 이미 있습니다. 덮어쓰려면 --force를 쓰세요.")
    OUTPUT.write_text(json.dumps(collect(), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"saved {OUTPUT}")


if __name__ == "__main__":
    main()
