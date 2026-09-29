STANDARD_VERSION = "DEMO-2026.1"

NUTRIENTS = {
    "CALCIUM": {"name": "칼슘", "unit": "MG"},
    "PHOSPHORUS": {"name": "인", "unit": "MG"},
    "VITAMIN_D": {"name": "비타민 D", "unit": "UG"},
    "VITAMIN_E": {"name": "비타민 E", "unit": "MG"},
    "OMEGA3": {"name": "오메가3", "unit": "MG"},
    "ZINC": {"name": "아연", "unit": "MG"},
}

# servingAmount 기준 데모 함량. 공개 전 공인 자료로 교체한다.
PRODUCTS = [
    {"id":"feed-balanced-dog","name":"데일리 밸런스 독","brand":"우애영 데모","type":"FEED","servingAmount":100,"servingUnit":"G","dataQuality":"COMPLETE","nutrients":{"CALCIUM":900,"PHOSPHORUS":720,"VITAMIN_D":6,"VITAMIN_E":10,"OMEGA3":180,"ZINC":12}},
    {"id":"feed-balanced-cat","name":"데일리 밸런스 캣","brand":"우애영 데모","type":"FEED","servingAmount":80,"servingUnit":"G","dataQuality":"COMPLETE","nutrients":{"CALCIUM":760,"PHOSPHORUS":650,"VITAMIN_D":5,"VITAMIN_E":9,"OMEGA3":220,"ZINC":10}},
    {"id":"feed-complete-unknown","name":"성분 미표기 완전사료","brand":"우애영 데모","type":"FEED","servingAmount":100,"servingUnit":"G","dataQuality":"MINIMUM_ONLY","nutrients":{}},
    {"id":"supp-calcium","name":"칼슘 플러스","brand":"우애영 데모","type":"SUPPLEMENT","servingAmount":1,"servingUnit":"TABLET","recommendedDailyAmount":1,"dataQuality":"COMPLETE","nutrients":{"CALCIUM":180,"PHOSPHORUS":60}},
    {"id":"supp-omega","name":"오메가 밸런스","brand":"우애영 데모","type":"SUPPLEMENT","servingAmount":1,"servingUnit":"CAPSULE","recommendedDailyAmount":1,"dataQuality":"COMPLETE","nutrients":{"OMEGA3":240,"VITAMIN_E":2}},
    {"id":"supp-multi","name":"데일리 멀티","brand":"우애영 데모","type":"SUPPLEMENT","servingAmount":1,"servingUnit":"TABLET","recommendedDailyAmount":1,"dataQuality":"COMPLETE","nutrients":{"VITAMIN_D":4,"VITAMIN_E":6,"ZINC":5}},
    {"id":"supp-zinc","name":"아연 케어","brand":"우애영 데모","type":"SUPPLEMENT","servingAmount":1,"servingUnit":"TABLET","recommendedDailyAmount":1,"dataQuality":"COMPLETE","nutrients":{"ZINC":7}},
]
PRODUCT_BY_ID = {product["id"]: product for product in PRODUCTS}

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

