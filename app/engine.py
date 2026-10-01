from __future__ import annotations

from copy import deepcopy
from hashlib import sha256
from uuid import uuid4

from .catalog import ENERGY_K, NUTRIENTS, PRODUCT_BY_ID, PRODUCTS, STANDARDS, STANDARD_VERSION
from .models import AnalysisRequest, ProductType, RecommendationRequest

UNIT_FACTOR = {"UG": 0.001, "MG": 1.0, "G": 1000.0}

class AnalysisError(ValueError):
    pass

def convert(value: float, source: str, target: str) -> float:
    source, target = source.upper(), target.upper()
    if source == target:
        return value
    if source not in UNIT_FACTOR or target not in UNIT_FACTOR:
        raise AnalysisError(f"변환할 수 없는 단위입니다: {source} → {target}")
    return value * UNIT_FACTOR[source] / UNIT_FACTOR[target]

def life_stage(request: AnalysisRequest) -> str:
    days = request.profile.age.days
    if request.profile.species.value == "CAT":
        return "GROWTH" if days < 365 else "ADULT"
    if days < 98:
        return "GROWTH_EARLY"
    return "GROWTH_LATE" if days < 365 else "ADULT"

def energy(request: AnalysisRequest) -> float:
    species = request.profile.species.value
    stage = life_stage(request)
    # FEDIAF의 생애주기별 에너지 기준 구조를 적용한다.
    # 세부 영양소 표는 catalog.py의 기준 버전과 함께 관리한다.
    factor = 95.0 if species == "DOG" and stage == "ADULT" else 110.0 if species == "DOG" else 75.0 if stage == "ADULT" else 100.0
    if species == "DOG" and stage != "ADULT":
        # FEDIAF 2025 Table VII-8b, 8주~1년: 예상 성체 체중 비율을 사용한다.
        factor = 254.1 - 135.0 * (request.profile.weightKg / request.profile.expectedAdultWeightKg)
    return factor * request.profile.weightKg ** (0.75 if species == "DOG" else 0.67)

def thresholds(request: AnalysisRequest, kcal: float) -> dict:
    species = request.profile.species.value
    if life_stage(request) != "ADULT":
        return {nutrient_id: {"minimum": None, "caution": None, "upper": None} for nutrient_id in STANDARDS[species]}
    caution_ratio = 0.75 if species == "DOG" else 0.5
    result = {}
    for nutrient_id, values in STANDARDS[species].items():
        minimum = kcal / 1000 * values["minimum"] if values.get("minimum") is not None else None
        upper = kcal / 1000 * values["upper"] if values.get("upper") is not None else None
        caution = kcal / 1000 * values["caution"] if values.get("caution") is not None else (upper * caution_ratio if upper else None)
        result[nutrient_id] = {"minimum": minimum, "caution": caution, "upper": upper}
    return result

def aggregate(request: AnalysisRequest, limits: dict) -> tuple[dict, bool, list[str], list[dict]]:
    totals = {key: {"fromFeed":0.0,"fromSupplements":0.0,"source":"ACTUAL"} for key in NUTRIENTS}
    estimated, warnings, contributions = False, [], []
    for item in request.items:
        product = PRODUCT_BY_ID.get(item.productId)
        if not product:
            raise AnalysisError(f"제품을 찾을 수 없습니다: {item.productId}")
        if item.unit.upper() != product["servingUnit"]:
            raise AnalysisError(f"{product['name']}의 급여 단위는 {product['servingUnit']}입니다.")
        bucket = "fromFeed" if product["type"] == "FEED" else "fromSupplements"
        ratio = item.dailyAmount / product["servingAmount"]
        if product["type"] == "FEED" and not product["nutrients"]:
            if request.profile.completeFeed:
                estimated = True
                values = {}
                for nutrient_id, line in limits.items():
                    if line["minimum"] is not None:
                        totals[nutrient_id][bucket] += line["minimum"]
                        totals[nutrient_id]["source"] = "ESTIMATED"
                        values[nutrient_id] = line["minimum"]
                contributions.append({"name":product["name"],"type":"FEED","source":"ESTIMATED","nutrients":values})
                warnings.append("사료 상세 성분이 없어 최소 권장량으로 추정했습니다.")
            else:
                contributions.append({"name":product["name"],"type":"FEED","source":"ACTUAL","nutrients":{}})
                warnings.append("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.")
        else:
            values = {}
            for nutrient_id, value in product["nutrients"].items():
                applied = value * ratio
                totals[nutrient_id][bucket] += applied
                values[nutrient_id] = applied
            contributions.append({"name":product["name"],"type":product["type"],"source":"ACTUAL","nutrients":values})
    for item in request.manualItems:
        bucket = "fromFeed" if item.type == ProductType.FEED else "fromSupplements"
        ratio, seen = item.dailyAmount / item.servingAmount, set()
        values = {}
        for nutrient in item.nutrients:
            if nutrient.nutrientId in seen:
                raise AnalysisError(f"중복 성분입니다: {nutrient.nutrientId}")
            seen.add(nutrient.nutrientId)
            meta = NUTRIENTS.get(nutrient.nutrientId)
            if not meta:
                raise AnalysisError(f"지원하지 않는 성분입니다: {nutrient.nutrientId}")
            applied = convert(nutrient.amount, nutrient.unit, meta["unit"]) * ratio
            totals[nutrient.nutrientId][bucket] += applied
            values[nutrient.nutrientId] = applied
        contributions.append({"name":item.name,"type":item.type.value,"source":"ACTUAL","nutrients":values})
    return totals, estimated, warnings, contributions

def classify(total: float, line: dict) -> str:
    if line["minimum"] is None and line["upper"] is None: return "NO_STANDARD"
    if line["minimum"] is not None and total < line["minimum"]: return "DEFICIENT"
    if line["upper"] is not None and total > line["upper"]: return "EXCESS"
    if line["caution"] is not None and total >= line["caution"]: return "CAUTION"
    return "ADEQUATE" if line["upper"] is not None else "ADEQUATE_NO_UPPER_LIMIT"

def analyze(request: AnalysisRequest) -> dict:
    kcal = energy(request)
    limits = thresholds(request, kcal)
    totals, estimated, warnings, contributions = aggregate(request, limits)
    nutrients, summary = [], {"deficient":0,"adequate":0,"caution":0,"excess":0,"noStandard":0}
    for nutrient_id, meta in NUTRIENTS.items():
        parts = totals[nutrient_id]
        total = parts["fromFeed"] + parts["fromSupplements"]
        status = classify(total, limits[nutrient_id])
        summary[{"NO_STANDARD":"noStandard","DEFICIENT":"deficient","CAUTION":"caution","EXCESS":"excess"}.get(status,"adequate")] += 1
        nutrients.append({"nutrientId":nutrient_id,"name":meta["name"],"unit":meta["unit"],**parts,"total":total,**limits[nutrient_id],"status":status})
    ratios = {}
    if request.profile.species.value == "DOG":
        calcium = next(x["total"] for x in nutrients if x["nutrientId"] == "CALCIUM")
        phosphorus = next(x["total"] for x in nutrients if x["nutrientId"] == "PHOSPHORUS")
        value = calcium / phosphorus if phosphorus else None
        ratios["calciumPhosphorus"] = {"value":value,"status":"UNAVAILABLE" if value is None else ("LOW" if value < 1 else "HIGH" if value > 2 else "ADEQUATE")}
    fingerprint = sha256(request.model_dump_json().encode()).hexdigest()[:16]
    result_warnings=warnings+["비타민 E와 오메가3는 현재 제품 단위가 공식 기준과 달라 기준 없음으로 표시하며 추천 점수에서 제외합니다.","상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 공식 상한을 적용하지 않았다는 뜻입니다."]
    if life_stage(request) != "ADULT": result_warnings.append("성장기 영양 기준선은 아직 지원하지 않아 모든 성분을 기준 없음으로 표시하고 영양제 추천을 제공하지 않습니다.")
    return {"traceId":f"{fingerprint}-{uuid4().hex[:8]}","standardVersion":STANDARD_VERSION,"standardSource":"FEDIAF Nutritional Guidelines 2025, adult values per 1000 kcal ME","lifeStage":life_stage(request),"referenceEnergyKcal":kcal,"usesEstimatedFeed":estimated,"summary":summary,"nutrients":nutrients,"contributions":contributions,"ratios":ratios,"warnings":result_warnings}

def recommend(request: RecommendationRequest) -> dict:
    base = analyze(request)
    if all(item["status"] == "NO_STANDARD" for item in base["nutrients"]):
        return {"traceId":base["traceId"],"standardVersion":STANDARD_VERSION,"message":"성장기 영양 기준선이 아직 지원되지 않아 추천 안전성을 판정할 수 없습니다.","items":[],"excluded":[],"usesEstimatedFeed":base["usesEstimatedFeed"]}
    current = {x["nutrientId"]:x["total"] for x in base["nutrients"]}
    limits = {x["nutrientId"]:{"minimum":x["minimum"],"caution":x["caution"],"upper":x["upper"]} for x in base["nutrients"]}
    original = {x["nutrientId"]:x["status"] for x in base["nutrients"]}
    selected, excluded = [], []
    remaining = [p for p in PRODUCTS if p["type"] == "SUPPLEMENT" and p.get("recommendedDailyAmount")]
    for _ in range(request.maxItems):
        safe = []
        for product in remaining:
            ratio = product["recommendedDailyAmount"] / product["servingAmount"]
            projected = deepcopy(current)
            for key, value in product["nutrients"].items(): projected[key] += value * ratio
            statuses = {key:classify(value, limits[key]) for key,value in projected.items()}
            harmful = [NUTRIENTS[key]["name"] for key,status in statuses.items() if key in product["nutrients"] and status in {"CAUTION","EXCESS"}]
            if harmful:
                excluded.append({"productId":product["id"],"name":product["name"],"reason":f"추가 후 주의·과다 예상: {', '.join(harmful)}"})
                continue
            fixed = sum(1 for key,status in original.items() if status == "DEFICIENT" and statuses[key] != "DEFICIENT")
            overlap = sum(1 for key in product["nutrients"] if original.get(key) not in {"DEFICIENT","NO_STANDARD"})
            margins = [(limits[k]["upper"]-projected[k])/(limits[k]["upper"]-limits[k]["minimum"]) for k in product["nutrients"] if limits[k]["upper"] and limits[k]["minimum"] and limits[k]["upper"] > limits[k]["minimum"]]
            margin = max(0.0, min(1.0, sum(margins)/len(margins))) if margins else 0.0
            score = 10 * fixed + 5 * margin - overlap
            safe.append((score,fixed,-len(product["nutrients"]),product["name"],product,projected,statuses))
        if not safe: break
        best = max(safe, key=lambda x:(x[0],x[1],x[2],x[3]))
        _, fixed, _, _, product, current, statuses = best
        selected.append({"productId":product["id"],"name":product["name"],"dailyAmount":product["recommendedDailyAmount"],"unit":product["servingUnit"],"score":round(best[0],3),"fixedNutrients":fixed,"projectedStatuses":statuses})
        remaining = [p for p in remaining if p["id"] != product["id"]]
        original = statuses
    return {"traceId":base["traceId"],"standardVersion":STANDARD_VERSION,"message":"현재 부족 성분은 없습니다. 안전 범위 후보입니다." if base["summary"]["deficient"] == 0 else "부족 성분과 안전 여유를 함께 고려했습니다.","items":selected,"excluded":list({x["productId"]:x for x in excluded}.values()),"usesEstimatedFeed":base["usesEstimatedFeed"]}

