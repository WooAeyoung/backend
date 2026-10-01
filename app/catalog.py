from pathlib import Path

from .catalog_index import ProductCatalog

STANDARD_VERSION = "DEMO-2026.1"

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

# 1000 kcal 기준 데모 값. 실제 급여 판단에 사용할 수 없다.
STANDARDS = {
    "DOG": {
        "CALCIUM":{"minimum":1000,"upper":2500}, "PHOSPHORUS":{"minimum":750,"upper":2000},
        "VITAMIN_D":{"minimum":12.5,"upper":80}, "VITAMIN_E":{"minimum":12,"upper":100},
        "OMEGA3":{"minimum":300,"upper":1200}, "ZINC":{"minimum":18,"upper":75},
    },
    "CAT": {
        "CALCIUM":{"minimum":1250,"upper":3000}, "PHOSPHORUS":{"minimum":1000,"upper":2500},
        "VITAMIN_D":{"minimum":10,"upper":75}, "VITAMIN_E":{"minimum":10,"upper":100},
        "OMEGA3":{"minimum":250,"upper":1000}, "ZINC":{"minimum":20,"upper":70},
    },
}
ENERGY_K = {"DOG": 95.0, "CAT": 100.0}

