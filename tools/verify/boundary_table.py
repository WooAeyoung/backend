#!/usr/bin/env python3
"""Generate the age, weight and large-breed boundary table for review."""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def rows():
    ages = [("dog", "7.9", "WEEK"), ("dog", "8", "WEEK"), ("dog", "13.9", "WEEK"),
            ("dog", "14", "WEEK"), ("cat", "4", "MONTH"), ("cat", "4.01", "MONTH"),
            ("cat", "9", "MONTH"), ("cat", "9.01", "MONTH"), ("cat", "11.99", "MONTH"),
            ("cat", "12", "MONTH")]
    for species, value, unit in ages:
        yield {"kind": "age", "species": species, "value": value, "unit": unit, "note": "life-stage boundary"}
    for weight in ("6.99", "7", "15", "15.01", "31.74", "31.75", "31.76"):
        yield {"kind": "weight", "species": "dog", "value": weight, "unit": "KG", "note": "size/large-breed boundary"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("boundary-table.csv"))
    args = parser.parse_args()
    records = list(rows())
    with args.output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=records[0])
        writer.writeheader(); writer.writerows(records)
    print(f"wrote {len(records)} rows to {args.output}")


if __name__ == "__main__":
    sys.exit(main())
