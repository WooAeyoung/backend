#!/usr/bin/env python3
"""Send JSON golden requests to the Spring API and report mismatches."""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


def compare(expected, actual, path="$", tolerance=1e-9, differences=None):
    differences = differences if differences is not None else []
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            differences.append((path, expected, actual)); return differences
        for key, value in expected.items():
            if key == "traceId":
                if not isinstance(actual.get(key), str) or not actual[key]:
                    differences.append((f"{path}.{key}", value, actual.get(key)))
                continue
            compare(value, actual.get(key), f"{path}.{key}", tolerance, differences)
    elif isinstance(expected, list):
        if not isinstance(actual, list) or len(expected) != len(actual):
            differences.append((path, expected, actual)); return differences
        for index, value in enumerate(expected):
            compare(value, actual[index], f"{path}[{index}]", tolerance, differences)
    elif isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        if not math.isclose(expected, actual, rel_tol=tolerance, abs_tol=tolerance):
            differences.append((path, expected, actual))
    elif expected != actual:
        differences.append((path, expected, actual))
    return differences


def call(base, case):
    url = base.rstrip("/") + case.get("path", "/api/v1/analyses")
    body = case.get("request")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    request = Request(url, data=data, method=case.get("method", "POST"), headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=15) as response:
            status, raw = response.status, response.read()
    except URLError as error:
        raise RuntimeError(f"API에 연결할 수 없습니다: {url} ({error})") from error
    return status, json.loads(raw.decode("utf-8")) if raw else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    parser.add_argument("--tolerance", type=float, default=1e-9)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    failures = []
    for case in cases:
        name = case["id"]
        try:
            status, actual = call(args.base, case)
            expected_status = case.get("status", 200)
            differences = [] if status == expected_status else [("$status", expected_status, status)]
            if status == expected_status and "response" in case:
                compare(case["response"], actual, tolerance=args.tolerance, differences=differences)
            if differences:
                failures.append((name, differences))
                print(f"FAIL {name}")
                for path, expected, received in differences:
                    print(f"  {path}: expected={expected!r} actual={received!r}")
            else:
                print(f"PASS {name}")
        except (RuntimeError, json.JSONDecodeError, KeyError) as error:
            failures.append((name, [("$error", "success", str(error))]))
            print(f"FAIL {name}: {error}")
    print(f"\n{len(cases) - len(failures)}/{len(cases)} cases passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
