"""FEDIAF 2025 회귀 방지용 대표 입력. 골든 파일은 리팩터링 전 코드로 한 번만 생성한다."""

def _feed(product, amount, unit="G"):
    return {"productId": product, "dailyAmount": amount, "unit": unit}

def _manual(name, nutrients, type_="SUPPLEMENT"):
    return {"name": name, "type": type_, "servingAmount": 1, "servingUnit": "TABLET", "dailyAmount": 1,
            "nutrients": [{"nutrientId": key, "amount": amount, "unit": unit} for key, amount, unit in nutrients]}

def _profile(species, weight, age, unit="MONTH", **extra):
    return {"name": "골든", "species": species, "weightKg": weight, "age": {"value": age, "unit": unit}, "completeFeed": True, **extra}

ADULT_DOG_10KG = _profile("DOG", 10, 36)
ADULT_CAT_4KG = _profile("CAT", 4, 36)

# (이름, 경로, 요청 본문)
CASES = [
    ("adult_dog_10kg_balanced_feed", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "items": [_feed("feed-balanced-dog", 150)]}),
    ("adult_dog_10kg_feed_and_supplements", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "items": [_feed("feed-balanced-dog", 150), _feed("supp-multi", 1, "TABLET"), _feed("supp-omega", 1, "CAPSULE")]}),
    ("adult_cat_4kg_balanced_feed", "/api/v1/analyses",
     {"profile": ADULT_CAT_4KG, "items": [_feed("feed-balanced-cat", 60)]}),
    ("growing_cat_6_months", "/api/v1/analyses",
     {"profile": _profile("CAT", 2.5, 6), "items": [_feed("feed-balanced-cat", 50)]}),
    ("puppy_early_growth_12_weeks", "/api/v1/analyses",
     {"profile": _profile("DOG", 3, 12, "WEEK", expectedAdultWeightKg=10), "items": [_feed("feed-balanced-dog", 90)]}),
    ("puppy_late_growth_large_breed_5_months", "/api/v1/analyses",
     {"profile": _profile("DOG", 12, 5, expectedAdultWeightKg=30), "items": [_feed("feed-balanced-dog", 300)]}),
    ("puppy_late_growth_small_breed_8_months", "/api/v1/analyses",
     {"profile": _profile("DOG", 6, 8, expectedAdultWeightKg=8), "items": [_feed("feed-balanced-dog", 120)]}),
    ("ca_p_ratio_exactly_upper_2_0", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "manualItems": [_manual("Ca:P 2.0", [("CALCIUM", 2000, "MG"), ("PHOSPHORUS", 1000, "MG")])]}),
    ("ca_p_ratio_above_upper", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "manualItems": [_manual("Ca:P 2.001", [("CALCIUM", 2.001, "G"), ("PHOSPHORUS", 1, "G")])]}),
    ("ca_p_ratio_exactly_lower_1_0", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "manualItems": [_manual("Ca:P 1.0", [("CALCIUM", 1000, "MG"), ("PHOSPHORUS", 1000, "MG")])]}),
    ("ca_p_ratio_below_lower", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "manualItems": [_manual("Ca:P 0.999", [("CALCIUM", 999, "MG"), ("PHOSPHORUS", 1000, "MG")])]}),
    ("missing_nutrient_feed_complete", "/api/v1/analyses",
     {"profile": ADULT_DOG_10KG, "items": [_feed("feed-complete-unknown", 150), _feed("supp-zinc", 1, "TABLET")]}),
    ("missing_nutrient_feed_not_complete", "/api/v1/analyses",
     {"profile": {**ADULT_DOG_10KG, "completeFeed": False}, "items": [_feed("feed-complete-unknown", 150)]}),
    ("recommend_adult_dog_low_feed", "/api/v1/recommendations",
     {"profile": ADULT_DOG_10KG, "items": [_feed("feed-balanced-dog", 20)], "maxItems": 3}),
    ("recommend_adult_cat_low_feed", "/api/v1/recommendations",
     {"profile": ADULT_CAT_4KG, "items": [_feed("feed-balanced-cat", 10)], "maxItems": 3}),
    ("recommend_estimated_feed", "/api/v1/recommendations",
     {"profile": ADULT_DOG_10KG, "items": [_feed("feed-complete-unknown", 150)], "maxItems": 2}),
]

GET_CASES = [
    ("health", "/health"),
    ("nutrients", "/api/v1/nutrients"),
]

# 실행마다 바뀌는 값
VOLATILE_KEYS = {"traceId"}
