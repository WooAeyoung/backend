"""Compare stable API fields between the Python reference and Java candidate servers."""

from __future__ import annotations

import json
import os
from math import isclose
from urllib.request import Request, urlopen

PYTHON_URL = os.getenv("PYTHON_API_URL", "http://localhost:8000").rstrip("/")
JAVA_URL = os.getenv("JAVA_API_URL", "http://localhost:8080").rstrip("/")

PAYLOAD = {
    "profile": {"name": "몽이", "species": "DOG", "weightKg": 6.2, "age": {"value": 24, "unit": "MONTH"}, "completeFeed": True},
    "items": [{"productId": "feed-balanced-dog", "dailyAmount": 80, "unit": "G"}],
    "manualItems": [],
}


def request(base: str, path: str, payload: dict | None = None) -> dict:
    body = None if payload is None else json.dumps(payload).encode()
    req = Request(base + path, data=body, method="GET" if body is None else "POST", headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=15) as response:
        return json.load(response)


def same_value(left: object, right: object) -> bool:
    if isinstance(left, (int, float)) and not isinstance(left, bool) and isinstance(right, (int, float)):
        return isclose(float(left), float(right), rel_tol=1e-9, abs_tol=1e-9)
    return left == right


def main() -> None:
    python = request(PYTHON_URL, "/api/v1/analyses", PAYLOAD)
    java = request(JAVA_URL, "/api/v1/analyses", PAYLOAD)
    failures = [field for field in ("standardVersion", "lifeStage", "usesEstimatedFeed", "summary", "ratios") if not same_value(python[field], java[field])]
    if not same_value(python["referenceEnergyKcal"], java["referenceEnergyKcal"]):
        failures.append("referenceEnergyKcal")
    python_nutrients = {item["nutrientId"]: item for item in python["nutrients"]}
    java_nutrients = {item["nutrientId"]: item for item in java["nutrients"]}
    for nutrient_id in python_nutrients:
        for field in ("total", "minimum", "caution", "upper", "status"):
            if not same_value(python_nutrients[nutrient_id][field], java_nutrients[nutrient_id][field]):
                failures.append(f"nutrients.{nutrient_id}.{field}")
    if failures:
        raise SystemExit("Backend parity failed: " + ", ".join(failures))
    print("Backend parity passed: Python reference == Java candidate")


if __name__ == "__main__":
    main()
