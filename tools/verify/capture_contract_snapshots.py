#!/usr/bin/env python3
"""Capture raw API responses for spring-backend/src/test/resources/contract/requests.json.

응답 본문을 바이트 그대로 contract/snapshots/{id}.json에 저장한다(재직렬화하지 않는다).
mode가 다른 케이스(default, strict)는 서버 설정이 달라 따로 실행한다:

    java -jar target/wooaeyoung-backend-1.0.0.jar                                     # default
    python tools/verify/capture_contract_snapshots.py --mode default
    WOOAEYOUNG_STANDARDS_ALLOW_UNVERIFIED=false java -jar target/wooaeyoung-backend-1.0.0.jar
    python tools/verify/capture_contract_snapshots.py --mode strict

스냅숏은 응답 계약을 바꾸지 않는다는 기준선이므로, 계약을 의도적으로 바꿀 때만 다시 만든다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

CONTRACT = Path(__file__).resolve().parents[2] / "spring-backend" / "src" / "test" / "resources" / "contract"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--mode", choices=["default", "strict"], required=True)
    parser.add_argument("--cases", default="requests.json", help="contract/ 아래 케이스 파일(requests.json, errors.json)")
    parser.add_argument("--out", type=Path, default=CONTRACT / "snapshots",
                        help="저장 위치. 저장소 스냅숏을 덮어쓰지 않고 JVM 간 비교만 할 때는 임시 디렉터리를 준다")
    args = parser.parse_args()
    cases = json.loads((CONTRACT / args.cases).read_text(encoding="utf-8"))
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    failures = 0
    for case in cases:
        if case["mode"] != args.mode:
            continue
        data = None if "body" not in case else json.dumps(case["body"], ensure_ascii=False).encode()
        if "rawBody" in case: data = case["rawBody"].encode()   # 깨진 JSON 등 그대로 보낼 본문
        request = Request(args.base.rstrip("/") + case["path"], data=data, method=case["method"], headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=15) as response:
                status, raw = response.status, response.read()
        except HTTPError as error:
            status, raw = error.code, error.read()
        expected = case.get("status", 200)   # 오류 계약 케이스는 기대 상태(400·422)를 적는다
        if status != expected:
            failures += 1
            print(f"FAIL {case['id']}: HTTP {status} (기대 {expected}) {raw.decode('utf-8', 'replace')}")
            continue
        (out / f"{case['id']}.json").write_bytes(raw)
        print(f"OK   {case['id']} ({len(raw)} bytes)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()