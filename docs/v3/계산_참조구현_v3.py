# -*- coding: utf-8 -*-
"""
계산 참조 구현 v3 — 계산명세서_v3.md의 함수를 그대로 옮긴 것.
표준 라이브러리만 사용. 실행하면 11장의 검산 케이스를 돌려 결과를 출력한다.

    python 계산_참조구현_v3.py

기준값은 기준값표_매개변수표_v3.xlsx의 값 중 검산에 필요한 성분만 아래에 박아 두었다.
실제 서비스에서는 엑셀(또는 JSON/CSV)에서 읽어 NUTRIENTS와 같은 구조로 만든다.
"""
import math

# ---------------------------------------------------------------- 매개변수표
P = {
    "exp": {"dog": 0.75, "cat": 0.67},
    "k_adult": {"dog": 110.0, "cat": 100.0},
    "pup_a": 254.1, "pup_b": 135.0,
    "cat_mult": {"cat_s1": 2.25, "cat_s2": 1.875, "cat_s3": 1.5},
    "caution_ratio": {"dog": 0.75, "cat": 0.50},
    "min_week": {"dog": 8, "cat": 8},
    "pup_early_week": 14,
    "cat_stage_month": {"s1": 4, "s2": 9},
    "adult_month": 12,
    "weeks_per_month": 4.345,
    "ca_large_kg": 31.75,
    "ca_max_large": 4.5,
    "ca_p_min": 1.0, "ca_p_max": 2.0,
    "feed_factor": 1.0,
    "w2": 10.0, "w3": 5.0, "w4": 1.0, "top_n": 3,
    "growth_curve": {
        "S":   {"a": 36.92, "b": 43.57, "lo": 0.0,  "hi": 7.0},
        "M":   {"a": 36.86, "b": 48.22, "lo": 7.0,  "hi": 15.0},
        "L":   {"a": 39.88, "b": 60.70, "lo": 15.0, "hi": 27.5},
        "XL":  {"a": 36.96, "b": 56.18, "lo": 27.5, "hi": 47.5},
        "XXL": {"a": 36.61, "b": 62.39, "lo": 47.5, "hi": None},
    },
}

# ---------------------------------------------------------------- 기준값표 (일부)
# min: dog_early, dog_late, dog_adult, cat_growth, cat_adult
# max: dog_growth, dog_adult, cat_growth, cat_adult
# caution: dog, cat  (예외 주의값)
def N(id_, name, unit, mn, mx, caution=(None, None)):
    return {"id": id_, "name": name, "unit": unit,
            "min": dict(zip(["dog_early", "dog_late", "dog_adult", "cat_growth", "cat_adult"], mn)),
            "max": dict(zip(["dog_growth", "dog_adult", "cat_growth", "cat_adult"], mx)),
            "caution": {"dog": caution[0], "cat": caution[1]}}

NUTRIENTS = {n["id"]: n for n in [
    N(7,  "메치오닌",  "g",  (0.88, 0.65, 0.83, 1.10, 0.43), (None, None, 3.75, None)),
    N(20, "칼슘",     "g",  (2.50, 2.00, 1.25, 2.50, 1.00), (6.25, 6.25, None, None)),
    N(21, "인",       "g",  (2.25, 1.75, 1.00, 2.00, 0.64), (None, 4.00, None, None)),
    N(27, "요오드",   "mg", (0.25, 0.25, 0.25, 0.45, 0.15), (2.75, 2.75, 2.25, 2.25)),
    N(30, "셀레늄",   "µg", (90.0, 90.0, 45.0, 75.0, 52.5), (500, 500, None, None), (None, 142.0)),
    N(31, "아연",     "mg", (25.0, 25.0, 18.0, 18.8, 18.8), (None, None, None, None), (56.75, 56.75)),
    N(32, "비타민 A", "IU", (1250, 1250, 1250, 1667, 833), (100000, 100000, 100000, 100000), (62500, None)),
    N(33, "비타민 D", "IU", (125, 125, 125, 70.0, 62.5), (800, 800, 7520, 7520)),
    N(34, "비타민 E", "IU", (12.5, 12.5, 9.0, 9.5, 9.5), (None, None, None, None)),
    N(44, "비타민 K", "µg", (None, None, None, None, None), (None, None, None, None)),
]}
CA, PH = 20, 21

STAGE_COLS = {  # 생애주기 -> (min열, max열)
    "dog_early": ("dog_early", "dog_growth"), "dog_late": ("dog_late", "dog_growth"),
    "dog_adult": ("dog_adult", "dog_adult"),
    "cat_s1": ("cat_growth", "cat_growth"), "cat_s2": ("cat_growth", "cat_growth"),
    "cat_s3": ("cat_growth", "cat_growth"), "cat_adult": ("cat_adult", "cat_adult"),
}


# ---------------------------------------------------------------- 1~4장
def normalize_age(value, unit):
    weeks = value if unit == "week" else value * P["weeks_per_month"]
    return weeks, weeks / P["weeks_per_month"]


def life_stage(species, weeks, months):
    if weeks < P["min_week"][species]:
        return "unsupported"
    if species == "dog":
        if weeks < P["pup_early_week"]:
            return "dog_early"
        return "dog_late" if months < P["adult_month"] else "dog_adult"
    if months <= P["cat_stage_month"]["s1"]:
        return "cat_s1"
    if months <= P["cat_stage_month"]["s2"]:
        return "cat_s2"
    return "cat_s3" if months < P["adult_month"] else "cat_adult"


class SizeClassRequired(Exception):
    pass


def puppy_growth(weight, weeks, size_class):
    if size_class is None:
        raise SizeClassRequired("체급 선택 필요")
    c = P["growth_curve"][size_class]
    pct = min(c["a"] * math.log(weeks) - c["b"], 100.0)
    adult_raw = weight / (pct / 100.0)
    adult = max(adult_raw, c["lo"])
    if c["hi"] is not None:
        adult = min(adult, c["hi"])
    warning = adult_raw < c["lo"] or (c["hi"] is not None and adult_raw > c["hi"])
    k = P["pup_a"] - P["pup_b"] * (pct / 100.0)
    return {"pct": pct, "adult_raw": adult_raw, "adult": adult, "k": k,
            "ca_large": adult >= P["ca_large_kg"], "warning": warning}


def energy(species, stage, weight, k):
    return k * weight ** P["exp"][species]


# ---------------------------------------------------------------- 5장
def thresholds(nut, species, stage, E, ca_large):
    mcol, xcol = STAGE_COLS[stage]
    min_ref, max_ref = nut["min"][mcol], nut["max"][xcol]
    if nut["id"] == CA and stage in ("dog_early", "dog_late") and ca_large:
        max_ref = P["ca_max_large"]
    lower = None if min_ref is None else E / 1000 * min_ref
    upper = None if max_ref is None else E / 1000 * max_ref
    exc = nut["caution"][species]
    if exc is not None:
        caution = E / 1000 * exc
    elif upper is not None:
        caution = upper * P["caution_ratio"][species]
    else:
        caution = None
    return lower, caution, upper


# ---------------------------------------------------------------- 6~8장
def intake(nid, lower, feeding, products, feed_factor):
    feed = feeding.get("feed")
    if feed and products[feed["product_id"]]["has_detail"] and nid in products[feed["product_id"]]["contents"]:
        feed_part = products[feed["product_id"]]["contents"][nid] * feed["grams_per_day"]
        est = False
    elif feeding.get("complete_feed", True) and lower is not None:
        feed_part, est = lower * feed_factor, True
    else:
        feed_part, est = 0.0, False
    supp = sum(products[s["product_id"]]["contents"].get(nid, 0.0) * s["doses_per_day"]
               for s in feeding.get("supplements", []))
    return feed_part + supp, feed_part, supp, est


def judge(total, lower, caution, upper, stage):
    if stage == "unsupported":
        return "미지원"
    if lower is None and upper is None and caution is None:
        return "기준 없음"
    if lower is not None and total < lower:
        return "부족"
    if upper is not None and total > upper:
        return "과다"
    if caution is not None and total >= caution:
        return "주의"
    return "적정" if upper is not None else "적정(상한 기준 없음)"


def ca_p_ratio(total_ca, total_p, species):
    if species != "dog":
        return "해당 없음", None
    if not total_p:
        return "계산 불가", None
    r = total_ca / total_p
    if r < P["ca_p_min"]:
        return "비율 낮음", r
    if r > P["ca_p_max"]:
        return "비율 높음", r
    return "적정", r


# ---------------------------------------------------------------- 9장
def analyze(profile, feeding, products=None, feed_factor=None):
    products = products or {}
    feed_factor = P["feed_factor"] if feed_factor is None else feed_factor
    sp = profile["species"]
    weeks, months = normalize_age(profile["age_value"], profile["age_unit"])
    stage = life_stage(sp, weeks, months)
    out = {"stage": stage, "E": None, "warning": False, "growth": None, "per_nutrient": {}, "ratio": None}
    if stage == "unsupported":
        out["per_nutrient"] = {nid: {"status": "미지원"} for nid in NUTRIENTS}
        return out
    ca_large = False
    if stage in ("dog_early", "dog_late"):
        g = puppy_growth(profile["weight_kg"], weeks, profile.get("size_class"))
        k, ca_large, out["warning"], out["growth"] = g["k"], g["ca_large"], g["warning"], g
    elif stage == "dog_adult":
        k = P["k_adult"]["dog"]
    elif stage == "cat_adult":
        k = P["k_adult"]["cat"]
    else:
        k = P["k_adult"]["cat"] * P["cat_mult"][stage]
    E = energy(sp, stage, profile["weight_kg"], k)
    out["E"] = E
    for nid, nut in NUTRIENTS.items():
        lo, ca, up = thresholds(nut, sp, stage, E, ca_large)
        total, fp, spp, est = intake(nid, lo, feeding, products, feed_factor)
        out["per_nutrient"][nid] = {"name": nut["name"], "lower": lo, "caution": ca, "upper": up,
                                    "total": total, "feed_part": fp, "supp_part": spp,
                                    "feed_estimated": est, "status": judge(total, lo, ca, up, stage)}
    if sp == "dog":
        out["ratio"] = ca_p_ratio(out["per_nutrient"][CA]["total"], out["per_nutrient"][PH]["total"], sp)
    return out


# ---------------------------------------------------------------- 10장 추천 (A안)
import heapq


def _dose_for(product, weight):
    for band in product.get("dose_by_weight", []):
        if weight <= band["max_kg"]:
            return band["dose"]
    return product.get("dose_by_weight", [{"dose": 1.0}])[-1]["dose"]


def recommend(profile, feeding, analysis, products):
    """analysis = analyze() 결과. products = 전체 제품 사전. 반환: [(product_id, score, fixed, margin, overlap)]"""
    if analysis["stage"] == "unsupported":
        return []
    stage, weight = analysis["stage"], profile["weight_kg"]
    pn = analysis["per_nutrient"]
    current = {i: pn[i]["total"] for i in pn}
    in_use = {s["product_id"] for s in feeding.get("supplements", [])}
    used_keys = set()
    for pid in in_use:
        used_keys |= set(products[pid]["contents"].keys())
    chosen = []

    def status_of(vals):
        return {i: judge(vals[i], pn[i]["lower"], pn[i]["caution"], pn[i]["upper"], stage) for i in pn}

    for _ in range(int(P["top_n"])):
        deficits = {i for i, st in status_of(current).items() if st == "부족"}
        heap = []
        for pid, c in products.items():
            if c["type"] != "supplement" or pid in in_use or pid in [x[0] for x in chosen]:
                continue
            dose = _dose_for(c, weight)
            trial = {i: current[i] + c["contents"].get(i, 0.0) * dose for i in pn}
            st = status_of(trial)
            if any(v in ("주의", "과다") for v in st.values()):
                continue
            fixed = sum(1 for i in deficits if st[i] != "부족")
            margins = []
            for i in c["contents"]:
                if i in pn and pn[i]["upper"] is not None and pn[i]["caution"] is not None:
                    lo, ca = pn[i]["lower"] or 0.0, pn[i]["caution"]
                    margins.append(1.0 if trial[i] < lo else (ca - trial[i]) / (ca - lo))
            margin = min(margins) if margins else 1.0
            overlap = len(set(c["contents"].keys()) & used_keys)
            score = P["w2"] * fixed + P["w3"] * margin - P["w4"] * overlap
            heapq.heappush(heap, (-score, pid, fixed, margin, overlap))
        if not heap:
            break
        neg, pid, fixed, margin, overlap = heapq.heappop(heap)
        chosen.append((pid, -neg, fixed, round(margin, 3), overlap))
        dose = _dose_for(products[pid], weight)
        for i in pn:
            current[i] += products[pid]["contents"].get(i, 0.0) * dose
        used_keys |= set(products[pid]["contents"].keys())
    return chosen


# ---------------------------------------------------------------- 검산
def _r(x, d=2):
    return None if x is None else round(x, d)


def _row(res, nid):
    n = res["per_nutrient"][nid]
    return f'{n["name"]}: lower {_r(n["lower"],3)} / caution {_r(n["caution"],3)} / upper {_r(n["upper"],3)}'


if __name__ == "__main__":
    prof = lambda sp, w, a, u, sc=None: {"species": sp, "weight_kg": w, "age_value": a, "age_unit": u, "size_class": sc}
    none = {"complete_feed": False}

    r = analyze(prof("dog", 10, 36, "month"), none)
    print("T1", r["stage"], _r(r["E"]), "|", _row(r, 33), "|", _row(r, 32), "|", _row(r, 31))
    r = analyze(prof("cat", 4, 36, "month"), none)
    print("T2", r["stage"], _r(r["E"]), "|", _row(r, 32), "|", _row(r, 27))
    r = analyze(prof("dog", 3, 12, "week", "S"), none)
    g = r["growth"]; print("T3", r["stage"], "pct", _r(g["pct"]), "adult", _r(g["adult"]), "k", _r(g["k"]), "E", _r(r["E"]), "|", _row(r, CA), "ca_large", g["ca_large"])
    r = analyze(prof("dog", 18, 20, "week", "XL"), none)
    g = r["growth"]; print("T4", r["stage"], "pct", _r(g["pct"]), "adult", _r(g["adult"]), "k", _r(g["k"]), "E", _r(r["E"]), "|", _row(r, CA), "ca_large", g["ca_large"])
    r = analyze(prof("dog", 40, 20, "week", "S"), none)
    g = r["growth"]; print("T5 adult_raw", _r(g["adult_raw"]), "adult", g["adult"], "warning", r["warning"], "ca_large", g["ca_large"], "E", _r(r["E"]), "|", _row(r, CA))
    r = analyze(prof("cat", 1.5, 3, "month"), none)
    print("T6", r["stage"], _r(r["E"]), "|", _row(r, 32), "|", _row(r, 7))
    r = analyze(prof("cat", 0.3, 2, "week"), none)
    print("T7", r["stage"], r["per_nutrient"][32]["status"])
    try:
        analyze(prof("dog", 3, 12, "week", None), none)
    except SizeClassRequired as e:
        print("T8 오류:", e)

    products = {"vd300": {"type": "supplement", "has_detail": False, "contents": {33: 300.0}}}
    feeding = {"complete_feed": True, "supplements": [{"product_id": "vd300", "doses_per_day": 1}]}
    r = analyze(prof("dog", 10, 36, "month"), feeding, products)
    n = r["per_nutrient"][33]; print("T9  비타민 D total", _r(n["total"]), "(feed", _r(n["feed_part"]), "추정" if n["feed_estimated"] else "", ") →", n["status"])
    r = analyze(prof("dog", 10, 36, "month"), feeding, products, feed_factor=2.7)
    n = r["per_nutrient"][33]; print("T10 비타민 D total", _r(n["total"]), "→", n["status"])
    r = analyze(prof("dog", 10, 36, "month"), {"complete_feed": False})
    print("T11 비타민 D", r["per_nutrient"][33]["status"], "| 비타민 K", r["per_nutrient"][44]["status"])
    print("T12", ca_p_ratio(1.2, 0.9, "dog")[0], "/", ca_p_ratio(2.5, 1.0, "dog")[0], "/", ca_p_ratio(1.2, 0.9, "cat")[0])
    r = analyze(prof("dog", 10, 36, "month"), {"complete_feed": True})
    n = r["per_nutrient"][34]; print("T13 비타민 E upper", n["upper"], "caution", n["caution"], "→", n["status"])

    cands = {
        "ca":  {"type": "supplement", "contents": {CA: 0.9, PH: 0.1}, "dose_by_weight": [{"max_kg": 99, "dose": 1}]},
        "vd":  {"type": "supplement", "contents": {33: 300.0},        "dose_by_weight": [{"max_kg": 99, "dose": 1}]},
        "ve":  {"type": "supplement", "contents": {34: 20.0},         "dose_by_weight": [{"max_kg": 99, "dose": 1}]},
    }
    pr = prof("dog", 10, 36, "month")
    a = analyze(pr, {"complete_feed": True}, cands)
    print("T14", recommend(pr, {"complete_feed": True}, a, cands), "| 부족:", [n["name"] for n in a["per_nutrient"].values() if n["status"] == "부족"])
    a = analyze(pr, {"complete_feed": False}, cands)
    print("T15", recommend(pr, {"complete_feed": False}, a, cands))
    print("T16", recommend(pr, {"complete_feed": True}, analyze(pr, {"complete_feed": True}), {}))
