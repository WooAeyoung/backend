#!/usr/bin/env python3
"""Measure end-to-end latency of the Spring API on localhost (BE-V3-020, 1회 측정용).

현재 카탈로그(products.json, 제품 7개·추천 후보 영양제 4개)로 만들 수 있는 최대 조건만 잰다.
요청 하나씩 순서대로 보내고(동시성 없음) 앞의 --warmup개는 버린다. 결과는 PR·문서에 적는 참고 수치이며 테스트가 아니다.

    java -jar spring-backend/target/wooaeyoung-backend-1.0.0.jar
    python tools/verify/measure_latency.py --requests 300   # 127.0.0.1:8080, 연결 재사용
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
from http.client import HTTPConnection

DOG = {"name": "측정", "species": "DOG", "weightKg": 10, "age": {"value": 36, "unit": "MONTH"}, "completeFeed": True}
CATALOG = [("feed-balanced-dog", 150, "G"), ("feed-complete-unknown", 50, "G"), ("supp-multi", 1, "TABLET"),
           ("supp-omega", 1, "CAPSULE"), ("supp-calcium", 1, "SCOOP"), ("supp-zinc", 1, "TABLET")]
# NIAS 분석 18개 성분과 라벨 전용 성분을 모두 담는 수동 입력(원료 형태·표기 기준 포함).
MANUAL = {"name": "측정 영양제", "type": "SUPPLEMENT", "servingAmount": 1, "servingUnit": "TABLET", "dailyAmount": 1, "nutrients": [
    {"nutrientId": "CALCIUM", "amount": 100, "unit": "mg"}, {"nutrientId": "PHOSPHORUS", "amount": 80, "unit": "mg"},
    {"nutrientId": "COPPER", "amount": 0.1, "unit": "mg"}, {"nutrientId": "IODINE", "amount": 10, "unit": "µg"},
    {"nutrientId": "IRON", "amount": 1, "unit": "mg"}, {"nutrientId": "MANGANESE", "amount": 0.1, "unit": "mg"},
    {"nutrientId": "SELENIUM", "amount": 5, "unit": "µg"}, {"nutrientId": "ZINC", "amount": 1, "unit": "mg"},
    {"nutrientId": "VITAMIN_A", "amount": 100, "unit": "IU"}, {"nutrientId": "VITAMIN_D", "amount": 10, "unit": "IU"},
    {"nutrientId": "VITAMIN_E", "amount": 1, "unit": "mg", "form": "DL_ALPHA_TOCOPHERYL_ACETATE"},
    {"nutrientId": "VITAMIN_B1", "amount": 0.1, "unit": "mg"}, {"nutrientId": "VITAMIN_B6", "amount": 0.1, "unit": "mg"},
    {"nutrientId": "VITAMIN_B12", "amount": 1, "unit": "µg"}, {"nutrientId": "VITAMIN_B9", "amount": 10, "unit": "µg"},
    {"nutrientId": "VITAMIN_B7", "amount": 1, "unit": "µg"}, {"nutrientId": "TAURINE", "amount": 10, "unit": "mg"},
    {"nutrientId": "EPA", "amount": 10, "unit": "mg"}, {"nutrientId": "DHA", "amount": 10, "unit": "mg"}]}


def items(count):
    return [{"productId": pid, "dailyAmount": amount, "unit": unit} for pid, amount, unit in (CATALOG * count)[:count]]


SCENARIOS = [
    ("FEDIAF 분석 · 제품 20개", "POST", "/api/v1/analyses", {"profile": DOG, "items": items(20)}),
    ("NIAS 분석 · 카탈로그 19개 + 수동 1개(18개 성분)", "POST", "/api/v1/analyses",
     {"profile": DOG, "items": items(19), "manualItems": [MANUAL], "standardProfile": "NIAS_V3"}),
    ("FEDIAF 추천 · 사료 1개", "POST", "/api/v1/recommendations", {"profile": DOG, "items": items(1)}),
    ("NIAS 추천 · 사료 1개(후보 4개, topN 3)", "POST", "/api/v1/recommendations", {"profile": DOG, "items": items(1), "standardProfile": "NIAS_V3"}),
    ("NIAS 조회 · 44개 성분", "GET", "/api/v2/standards/NIAS_V3", None),
]


def once(connection, method, path, body):
    """연결 하나를 재사용(keep-alive)해 연결 수립 비용과 Windows 타이머 해상도(약 15.6 ms) 영향을 뺀다."""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    start = time.perf_counter()
    connection.request(method, path, body=data, headers={"Content-Type": "application/json"})
    response = connection.getresponse()
    response.read()
    elapsed = (time.perf_counter() - start) * 1000
    if response.status != 200: raise RuntimeError(f"{path}: HTTP {response.status}")
    return elapsed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--requests", type=int, default=300)
    parser.add_argument("--warmup", type=int, default=50)
    args = parser.parse_args()
    connection = HTTPConnection(args.host, args.port, timeout=30)
    print(f"| 시나리오 | 요청 수 | p50 ms | p95 ms | 최대 ms |\n| --- | --- | --- | --- | --- |")
    for name, method, path, body in SCENARIOS:
        for _ in range(args.warmup): once(connection, method, path, body)
        samples = sorted(once(connection, method, path, body) for _ in range(args.requests))
        p95 = samples[int(len(samples) * 0.95) - 1]
        print(f"| {name} | {len(samples)} | {statistics.median(samples):.2f} | {p95:.2f} | {samples[-1]:.2f} |")


if __name__ == "__main__":
    main()
