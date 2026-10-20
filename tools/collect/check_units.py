#!/usr/bin/env python3
"""Check collected label samples against the reviewed unit factor table."""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=Path, default=Path(__file__).with_name("label_samples.csv"))
    parser.add_argument("--units", type=Path, default=Path(__file__).with_name("unit_factors.csv"))
    args = parser.parse_args()
    with args.units.open(encoding="utf-8", newline="") as stream:
        allowed = {row["unit"].strip().upper().replace("Μ", "Μ") for row in csv.DictReader(stream)}
    unknown = []
    with args.samples.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            unit = row["unit"].strip().upper()
            if unit and unit not in allowed:
                unknown.append((row["sample_id"], row["nutrient_id"], row["unit"]))
    if unknown:
        print("단위 추가 검토 목록:")
        for item in unknown: print(" -", item)
        return 1
    print("모든 표본 단위가 unit_factors.csv에 있습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
