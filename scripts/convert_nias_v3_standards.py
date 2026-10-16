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
NOT_APPLICABLE = "NOT_APPLICABLE"
UNCONFIRMED = "UNCONFIRMED"  # 원문에서 표 번호를 확인할 수 없음
STANDARDS_SHEET = "기준값표"
MASTER_FILE = OUTPUT_DIR.parent / "nutrients-master.json"
BASIS = "per_1000kcal_ME"
NIAS_DOCUMENT = "농촌진흥청 국립축산과학원 「반려동물 사료 영양표준」"
AAFCO_DOCUMENT = "AAFCO Dog and Cat Food Nutrient Profiles (2014 revised final)"
EU_DOCUMENT = "FEDIAF Nutritional Guidelines 2021 (EU legal maximum)"
ALLOWED_UNITS = {"g", "mg", "µg", "IU"}

# 성분 ID → 문자열 키. 2026-10-16 사용자 승인 목록. 기존 6개 키(CALCIUM, PHOSPHORUS, VITAMIN_D, VITAMIN_E, ZINC; OMEGA3는 FEDIAF 전용)는 바꾸지 않는다.
KEYS = {
    1: "PROTEIN", 2: "ARGININE", 3: "HISTIDINE", 4: "ISOLEUCINE", 5: "LEUCINE", 6: "LYSINE", 7: "METHIONINE",
    8: "METHIONINE_CYSTINE", 9: "PHENYLALANINE", 10: "PHENYLALANINE_TYROSINE", 11: "THREONINE", 12: "TRYPTOPHAN",
    13: "VALINE", 14: "TAURINE", 15: "FAT", 16: "LINOLEIC_ACID", 17: "ARACHIDONIC_ACID", 18: "ALPHA_LINOLENIC_ACID",
    19: "EPA_DHA", 20: "CALCIUM", 21: "PHOSPHORUS", 22: "POTASSIUM", 23: "SODIUM", 24: "CHLORIDE", 25: "MAGNESIUM",
    26: "COPPER", 27: "IODINE", 28: "IRON", 29: "MANGANESE", 30: "SELENIUM", 31: "ZINC", 32: "VITAMIN_A",
    33: "VITAMIN_D", 34: "VITAMIN_E", 35: "VITAMIN_B1", 36: "VITAMIN_B2", 37: "VITAMIN_B5", 38: "VITAMIN_B6",
    39: "VITAMIN_B12", 40: "VITAMIN_B3", 41: "VITAMIN_B9", 42: "VITAMIN_B7", 43: "CHOLINE", 44: "VITAMIN_K",
}
# 기준값표 열 → (구분, 생애주기 열, 원문 표). 표 번호는 안내 시트 A14의 열별 출처.
MINIMUM_COLUMNS = {"D": "dog_early", "E": "dog_late", "F": "dog_adult", "G": "cat_growth", "H": "cat_adult"}
MAXIMUM_COLUMNS = {"I": "dog_growth", "J": "dog_adult", "K": "cat_growth", "L": "cat_adult"}
CAUTION_COLUMNS = {"M": "dog", "N": "cat"}
TABLES = {"dog_early": "2-17c", "dog_late": "2-17c", "dog_growth": "2-17c", "dog_adult": "2-17d", "cat_growth": "2-17g", "cat_adult": "2-17h"}

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


def record(value, unit: str, *, document: str, table: str, year: int | None, status: str, value_type: str, note: str) -> dict:
    return {"value": value, "unit": unit, "basis": BASIS, "sourceDocument": document, "table": table, "sourceYear": year,
            "verificationStatus": status, "verifiedAt": None, "valueType": value_type, "note": note}


def nutrient_rows(cells: dict[str, str]) -> list[dict[str, str]]:
    by_row = rows(cells)
    result = []
    for number_, row in sorted(by_row.items()):
        if row.get("A", "").isdigit():
            row = {**row, "_row": str(number_)}
            result.append(row)
    ids = [int(row["A"]) for row in result]
    if ids != list(range(1, 45)):
        raise SystemExit(f"기준값표 성분 ID가 1~44 연속이 아닙니다: {ids}")
    return result


def standard_value(row: dict[str, str], column: str, stage: str, unit: str) -> dict:
    cell = f"{STANDARDS_SHEET}!{column}{row['_row']}"
    text = row.get(column)
    if text is None:
        return record(None, unit, document=NIAS_DOCUMENT, table="N/A", year=None, status=NOT_APPLICABLE, value_type="LITERATURE",
                      note=f"표 {TABLES[stage]}에 값 없음({cell} 빈칸) → 기준 없음")
    table = TABLES[stage]
    note = cell
    if KEYS[int(row["A"])] == "CALCIUM" and stage == "dog_growth":
        note += "; 예상 성체 31.75 kg 이상은 4.5 적용(표 2-17c 각주 d, parameters.json largeBreedCalcium)"
    return record(number(text), unit, document=NIAS_DOCUMENT, table=table, year=None, status=PENDING, value_type="LITERATURE", note=note)


def caution_value(row: dict[str, str], column: str, species: str, unit: str) -> dict:
    cell = f"{STANDARDS_SHEET}!{column}{row['_row']}"
    text = row.get(column)
    if text is None:
        return record(None, unit, document=SOURCE_DOCUMENT, table="N/A", year=None, status=NOT_APPLICABLE, value_type="LITERATURE",
                      note=f"예외 주의값 없음({cell} 빈칸) → NIAS 최대 × 주의 비율, 최대도 없으면 주의선 없음")
    key = KEYS[int(row["A"])]
    if key == "VITAMIN_A" and species == "dog":
        return record(number(text), unit, document=AAFCO_DOCUMENT, table=UNCONFIRMED, year=2014, status=PENDING, value_type="LITERATURE",
                      note=f"{cell}; 250,000 IU/kg 건물 ÷ 4 = 62,500 IU/1000 kcal(안내 A15)")
    return record(number(text), unit, document=EU_DOCUMENT, table=UNCONFIRMED, year=2021, status=PENDING, value_type="LITERATURE",
                  note=f"{cell}; EU 법정값(건물 100 g당) × 2.5(영양표준 표 2-15 환산). 건물 100 g당 원값은 FEDIAF 원문 대조 필요. 비고: {row.get('Q', '')}")


def nutrients(cells: dict[str, str]) -> tuple[dict, dict]:
    master, profile = [], []
    for row in nutrient_rows(cells):
        identifier, unit = int(row["A"]), row["C"]
        if unit not in ALLOWED_UNITS:
            raise SystemExit(f"허용하지 않는 단위: {unit} (ID {identifier})")
        key = KEYS[identifier]
        master.append({"id": identifier, "key": key, "name": row["B"], "category": row["P"], "baseUnit": unit})
        profile.append({
            "key": key,
            "unit": unit,
            "implemented": row.get("O") == "예",
            "note": row.get("Q"),
            "minimum": {stage: standard_value(row, column, stage, unit) for column, stage in MINIMUM_COLUMNS.items()},
            "maximum": {stage: standard_value(row, column, stage, unit) for column, stage in MAXIMUM_COLUMNS.items()},
            "caution": {species: caution_value(row, column, species, unit) for column, species in CAUTION_COLUMNS.items()},
        })
    master_file = {"description": "영양소 마스터(NIAS 성분 ID 1~44). 프로필은 key로 참조한다.", "sourceDocument": f"{SOURCE_DOCUMENT} {STANDARDS_SHEET}",
                   "nutrients": master}
    profile_file = {"profile": "NIAS_V3", "basis": BASIS, "sourceDocument": f"{SOURCE_DOCUMENT} {STANDARDS_SHEET}", "nutrients": profile}
    return master_file, profile_file


def render(data: dict) -> str:
    """Pretty JSON, but each standard value record stays on one line for review."""
    def write(value, indent: int) -> str:
        pad, inner = " " * indent, " " * (indent + 2)
        if isinstance(value, dict) and "verificationStatus" in value and "basis" in value:
            return json.dumps(value, ensure_ascii=False)
        if isinstance(value, dict):
            if not value:
                return "{}"
            return "{\n" + ",\n".join(f"{inner}{json.dumps(k, ensure_ascii=False)}: {write(v, indent + 2)}" for k, v in value.items()) + f"\n{pad}}}"
        if isinstance(value, list):
            if not value:
                return "[]"
            return "[\n" + ",\n".join(f"{inner}{write(v, indent + 2)}" for v in value) + f"\n{pad}]"
        return json.dumps(value, ensure_ascii=False)
    return write(data, 0) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="커밋된 JSON이 xlsx 변환 결과와 같은지만 확인한다")
    args = parser.parse_args()
    master, profile = nutrients(read_sheet(WORKBOOK, STANDARDS_SHEET))
    outputs = {OUTPUT_DIR / "parameters.json": render(parameters(read_sheet(WORKBOOK, PARAMETERS_SHEET))),
               MASTER_FILE: render(master),
               OUTPUT_DIR / "nutrients.json": render(profile)}
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
