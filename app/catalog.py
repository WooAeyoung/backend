from pathlib import Path

from .catalog_index import ProductCatalog

STANDARD_VERSION = "FEDIAF-2025.09"

NUTRIENTS = {
    "CALCIUM": {"name": "칼슘", "unit": "MG"},
    "PHOSPHORUS": {"name": "인", "unit": "MG"},
    "VITAMIN_D": {"name": "비타민 D", "unit": "UG"},
    "VITAMIN_E": {"name": "비타민 E", "unit": "MG"},
    "OMEGA3": {"name": "오메가3", "unit": "MG"},
    "ZINC": {"name": "아연", "unit": "MG"},
}

# 앱 시작 시 JSON 파일을 한 번 읽고 해시 테이블과 트라이를 메모리에 구성한다.
CATALOG = ProductCatalog.from_json(Path(__file__).parents[1] / "data" / "products.json")
PRODUCTS = CATALOG.products
PRODUCT_BY_ID = CATALOG.by_id

# FEDIAF Nutritional Guidelines 2025 생애주기별 1000 kcal ME 기준.
# 현재 제품 단위와 직접 비교할 수 없는 비타민 E와 오메가3는 기준 없음으로 둔다.
STANDARDS = {
    "DOG": {
        "ADULT": {"CALCIUM":{"minimum":1450,"upper":6250}, "PHOSPHORUS":{"minimum":1160,"upper":4000}, "VITAMIN_D":{"minimum":3.975,"upper":20}, "VITAMIN_E":{"minimum":None,"upper":None}, "OMEGA3":{"minimum":None,"upper":None}, "ZINC":{"minimum":20.8,"upper":None}},
        "GROWTH_EARLY": {"CALCIUM":{"minimum":2500,"upper":4000}, "PHOSPHORUS":{"minimum":2250,"upper":None}, "VITAMIN_D":{"minimum":3.45,"upper":20}, "VITAMIN_E":{"minimum":None,"upper":None}, "OMEGA3":{"minimum":None,"upper":None}, "ZINC":{"minimum":25,"upper":None}},
        "GROWTH_LATE": {"CALCIUM":{"minimum":2000,"upper":4500}, "PHOSPHORUS":{"minimum":1750,"upper":None}, "VITAMIN_D":{"minimum":3.125,"upper":20}, "VITAMIN_E":{"minimum":None,"upper":None}, "OMEGA3":{"minimum":None,"upper":None}, "ZINC":{"minimum":25,"upper":None}},
    },
    "CAT": {
        "ADULT": {"CALCIUM":{"minimum":1330,"upper":None}, "PHOSPHORUS":{"minimum":850,"upper":None}, "VITAMIN_D":{"minimum":2.0825,"upper":187.5}, "VITAMIN_E":{"minimum":None,"upper":None}, "OMEGA3":{"minimum":None,"upper":None}, "ZINC":{"minimum":25,"upper":None}},
        "GROWTH": {"CALCIUM":{"minimum":2500,"upper":None}, "PHOSPHORUS":{"minimum":2100,"upper":None}, "VITAMIN_D":{"minimum":1.75,"upper":187.5}, "VITAMIN_E":{"minimum":None,"upper":None}, "OMEGA3":{"minimum":None,"upper":None}, "ZINC":{"minimum":18.8,"upper":None}},
    },
}
ENERGY_K = {"DOG": 95.0, "CAT": 100.0}

