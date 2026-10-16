"""Convert docs/v3/기준값표_매개변수표_v3.xlsx into the Java NIAS_V3 standard data files.

Standard library only (the xlsx is read as a zip of XML parts).

    python scripts/convert_nias_v3_standards.py           # write JSON
    python scripts/convert_nias_v3_standards.py --check   # fail if committed JSON differs from the xlsx

Values are copied as they appear in the sheet. Every NIAS value is PENDING_VERIFICATION until it is
checked against the original publication.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKBOOK = ROOT / "docs" / "v3" / "기준값표_매개변수표_v3.xlsx"
OUTPUT_DIR = ROOT / "spring-backend" / "src" / "main" / "resources" / "standards" / "nias-v3"
PARAMETERS_SHEET = "매개변수표"
SOURCE_DOCUMENT = "docs/v3/기준값표_매개변수표_v3.xlsx"
PARAMETER_VERSION = "NIAS-v3+params.2026-09-27"  # 안내 시트 A2의 작성일
PENDING = "PENDING_VERIFICATION"

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_ID = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"


def read_sheet(path: Path, name: str) -> dict[str, str]:
    """Return {cell reference: text} for one sheet."""
    with zipfile.ZipFile(path) as archive:
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            for item in ET.fromstring(archive.read("xl/sharedStrings.xml")).findall("m:si", NS):
                shared.append("".join(text.text or "" for text in item.iter("{%s}t" % NS["m"])))
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        relations = {rel.get("Id"): rel.get("Target") for rel in ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))}
        for sheet in workbook.find("m:sheets", NS):
            if sheet.get("name") != name:
                continue
            target = relations[sheet.get(REL_ID)].lstrip("/")
            target = target if target.startswith("xl/") else "xl/" + target
            cells = {}
            for cell in ET.fromstring(archive.read(target)).iter("{%s}c" % NS["m"]):
                value = cell.find("m:v", NS)
                if cell.get("t") == "s" and value is not None:
                    text = shared[int(value.text)]
                elif cell.get("t") == "inlineStr":
                    text = "".join(part.text or "" for part in cell.iter("{%s}t" % NS["m"]))
                else:
                    text = None if value is None else value.text
                if text not in (None, ""):
                    cells[cell.get("r")] = text
            return cells
    raise SystemExit(f"시트를 찾을 수 없습니다: {name}")


def rows(cells: dict[str, str]) -> dict[int, dict[str, str]]:
    result: dict[int, dict[str, str]] = {}
    for reference, text in cells.items():
        column, row = re.fullmatch(r"([A-Z]+)(\d+)", reference).groups()
        result.setdefault(int(row), {})[column] = text
    return result


def number(text: str | None) -> float | int | None:
    if text is None:
        return None
    value = float(text)
    return int(value) if value.is_integer() else value


def parameters(cells: dict[str, str]) -> dict:
    by_row = rows(cells)
    table = {row["C"]: row for row in by_row.values() if "C" in row and "D" in row and row["C"] != "키(코드용)" and "A" in row and row["A"] not in GROWTH_CLASSES}
    required = ["exp_dog", "exp_cat", "k_dog_adult", "k_cat_adult", "k_pup_a", "k_pup_b", "cat_mult_1", "cat_mult_2", "cat_mult_3",
                "caution_dog", "caution_cat", "min_week_dog", "min_week_cat", "pup_early_week", "cat_stage1_month", "cat_stage2_month",
                "adult_month", "weeks_per_month", "ca_large_kg", "ca_max_large", "ca_p_min", "ca_p_max", "feed_factor", "w2", "w3", "w4", "top_n"]
    missing = [key for key in required if key not in table]
    if missing:
        raise SystemExit("매개변수표에 없는 키: " + ", ".join(missing))

    def value(key: str):
        return number(table[key]["D"])

    def source(*keys: str) -> str:
        return f"{SOURCE_DOCUMENT} {PARAMETERS_SHEET}: " + "; ".join(f"{key}={table[key]['D']} ({table[key].get('G', '근거 칸 비어 있음')})" for key in keys)

    def section(keys: list[str], **values) -> dict:
        return {**values, "verificationStatus": PENDING, "source": source(*keys)}

    growth = {row["A"]: row for row in by_row.values() if row.get("A") in GROWTH_CLASSES}
    if sorted(growth) != sorted(GROWTH_CLASSES):
        raise SystemExit("성장곡선 체급 행이 부족합니다: " + ", ".join(sorted(growth)))
    curve_note = next((row["A"] for row in by_row.values() if row.get("A", "").startswith("개 성장곡선")), "")
    return {
        "profile": "NIAS_V3",
        "parameterVersion": PARAMETER_VERSION,
        "description": "scripts/convert_nias_v3_standards.py가 매개변수표 시트에서 변환했다. 모든 섹션은 원문 대조 전이다.",
        "sourceDocument": SOURCE_DOCUMENT,
        "age": section(["weeks_per_month", "min_week_dog", "min_week_cat", "pup_early_week", "cat_stage1_month", "cat_stage2_month", "adult_month"],
                       weeksPerMonth=value("weeks_per_month"), dogMinimumWeeks=value("min_week_dog"), catMinimumWeeks=value("min_week_cat"),
                       dogEarlyGrowthEndWeeks=value("pup_early_week"), catStage1EndMonths=value("cat_stage1_month"),
                       catStage2EndMonths=value("cat_stage2_month"), adultMonths=value("adult_month")),
        "energy": section(["exp_dog", "exp_cat", "k_dog_adult", "k_cat_adult", "k_pup_a", "k_pup_b", "cat_mult_1", "cat_mult_2", "cat_mult_3"],
                          exponent={"dog": value("exp_dog"), "cat": value("exp_cat")}, dogAdultK=value("k_dog_adult"), catAdultK=value("k_cat_adult"),
                          puppyIntercept=value("k_pup_a"), puppySlope=value("k_pup_b"), catStage1Multiplier=value("cat_mult_1"),
                          catStage2Multiplier=value("cat_mult_2"), catStage3Multiplier=value("cat_mult_3")),
        "growthCurve": {
            "sizeClasses": {key: {"a": number(growth[key]["C"]), "b": number(growth[key]["D"]), "lowerKg": number(growth[key]["E"]),
                                  "upperKg": number(growth[key].get("F"))} for key in GROWTH_CLASSES},
            "verificationStatus": PENDING,
            "source": f"{SOURCE_DOCUMENT} {PARAMETERS_SHEET}: {curve_note}",
        },
        "largeBreedCalcium": section(["ca_large_kg", "ca_max_large"], expectedAdultWeightAtLeastKg=value("ca_large_kg"),
                                     dogGrowthCalciumMaximum=value("ca_max_large")),
        "cautionRatio": section(["caution_dog", "caution_cat"], ratio={"dog": value("caution_dog"), "cat": value("caution_cat")}),
        "calciumPhosphorus": section(["ca_p_min", "ca_p_max"], minimum=value("ca_p_min"), maximum=value("ca_p_max")),
        "feedEstimate": section(["feed_factor"], feedFactor=value("feed_factor")),
        "recommendation": section(["w2", "w3", "w4", "top_n"], w2=value("w2"), w3=value("w3"), w4=value("w4"), topN=value("top_n")),
    }


GROWTH_CLASSES = ["S", "M", "L", "XL", "XXL"]


def render(data: dict) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="커밋된 JSON이 xlsx 변환 결과와 같은지만 확인한다")
    args = parser.parse_args()
    outputs = {OUTPUT_DIR / "parameters.json": render(parameters(read_sheet(WORKBOOK, PARAMETERS_SHEET)))}
    if args.check:
        stale = [str(path.relative_to(ROOT)) for path, text in outputs.items()
                 if not path.exists() or path.read_text(encoding="utf-8").replace("\r\n", "\n") != text]
        if stale:
            raise SystemExit("xlsx와 다른 파일: " + ", ".join(stale))
        print("NIAS v3 standard files match the xlsx")
        return
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for path, text in outputs.items():
        with path.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        print("wrote", path.relative_to(ROOT))


if __name__ == "__main__":
    sys.exit(main())
