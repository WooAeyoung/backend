#!/usr/bin/env python3
"""Generate a feed-amount sensitivity input table for a 10 kg adult dog."""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("sensitivity-10kg-dog.csv"))
    args = parser.parse_args()
    with args.output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(("species", "weight_kg", "age_months", "feed_g", "mode"))
        writer.writerow(("DOG", 10, 36, "", "assumed_energy_baseline"))
        for grams in range(10, 251, 10):
            writer.writerow(("DOG", 10, 36, grams, "explicit_feed_amount"))
    print(f"wrote 26 rows to {args.output}")


if __name__ == "__main__":
    sys.exit(main())
