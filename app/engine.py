from __future__ import annotations

import math
from copy import deepcopy
from hashlib import sha256
from uuid import uuid4

from .catalog import PRODUCT_BY_ID, PRODUCTS
from .models import AnalysisRequest, ProductType, RecommendationRequest
from .standards.loader import conversion_factor
from .standards.models import StandardProfile, VerificationStatus
from .standards.registry import NUTRIENT_CATALOG, allow_unverified_standards, get_profile, master

UNIT_FACTOR = {"UG": 0.001, "MG": 1.0, "G": 1000.0}
UNVERIFIED_STANDARD = "UNVERIFIED_STANDARD"

class AnalysisError(ValueError):
    pass

def convert(value: float, source: str, target: str) -> float:
    source, target = source.upper(), target.upper()
    if source == target:
        return value
    if source not in UNIT_FACTOR or target not in UNIT_FACTOR:
        raise AnalysisError(f"변환할 수 없는 단위입니다: {source} → {target}")
    return value * UNIT_FACTOR[source] / UNIT_FACTOR[target]

def profile_of(request: AnalysisRequest, profile: StandardProfile | None = None) -> StandardProfile:
    return profile or get_profile(getattr(request, "standardProfile", None))

def nutrient_units(profile: StandardProfile) -> dict[str, str]:
    return {item.id: item.unit for item in profile.nutrients}

class ParamTracker:
    """계산에 실제로 쓴 매개변수를 기록해 응답의 검증 요약에 남긴다."""
    def __init__(self, profile: StandardProfile):
        self.profile, self.used = profile, {}
    def __call__(self, name: str) -> float:
        self.used[name] = self.profile.params[name]
        return self.profile.param(name)
    def growth(self, size: str, name: str) -> float:
        param = getattr(self.profile.growthCurves[size], name)
        self.used[f"growthCurves.{size}.{name}"] = param
        return param.value

def _fediaf_days(request: AnalysisRequest, p: ParamTracker) -> float:
    age = request.profile.age
    return age.value * (7 if age.unit == "WEEK" else p("daysPerMonth"))

def _nias_age(request: AnalysisRequest, p: ParamTracker) -> tuple[float, float]:
    age = request.profile.age
    weeks = age.value if age.unit == "WEEK" else age.value * p("weeksPerMonth")
    return weeks, weeks / p("weeksPerMonth")

def life_stage(request: AnalysisRequest, profile: StandardProfile | None = None, p: ParamTracker | None = None) -> str:
    profile = profile_of(request, profile)
    p = p or ParamTracker(profile)
    species = request.profile.species.value
    if profile.model == "FEDIAF_2025":
        days = _fediaf_days(request, p)
        if species == "CAT":
            return "GROWTH" if days < p("adultAgeDays") else "ADULT"
        if days < p("dogEarlyGrowthDays"):
            return "GROWTH_EARLY"
        return "GROWTH_LATE" if days < p("adultAgeDays") else "ADULT"
    weeks, months = _nias_age(request, p)
    if weeks < p(f"minimumAgeWeeks{species.title()}"):
        raise AnalysisError("8주 미만 개체는 현재 지원하지 않습니다.")
    if species == "DOG":
        if weeks < p("dogEarlyGrowthWeeks"):
            return "DOG_GROWTH_EARLY"
        return "DOG_GROWTH_LATE" if months < p("adultMonths") else "DOG_ADULT"
    if months <= p("catGrowthStage1Months"):
        return "CAT_GROWTH_1"
    if months <= p("catGrowthStage2Months"):
        return "CAT_GROWTH_2"
    return "CAT_GROWTH_3" if months < p("adultMonths") else "CAT_ADULT"

def _puppy_growth(request: AnalysisRequest, p: ParamTracker) -> dict:
    size = request.profile.adultSize
    if size is None:
        raise AnalysisError("12개월 미만 개는 예상 성체 체급(adultSize: S, M, L, XL, XXL)이 필요합니다.")
    weeks, _ = _nias_age(request, p)
    curve = p.profile.growthCurves[size]
    percent = min(p.growth(size, "a") * math.log(weeks) - p.growth(size, "b"), 100.0)
    if percent <= 0:
        raise AnalysisError("성장곡선 비율을 계산할 수 없는 주령입니다.")
    raw = request.profile.weightKg / (percent / 100.0)
    adult = max(raw, curve.adultWeightKgAbove)
    if curve.adultWeightKgAtMost is not None:
        adult = min(adult, curve.adultWeightKgAtMost)
    out_of_range = raw < curve.adultWeightKgAbove or (curve.adultWeightKgAtMost is not None and raw > curve.adultWeightKgAtMost)
    factor = p("puppyEnergyIntercept") - p("puppyEnergySlopePerPercent") * percent
    return {"sizeClass": size, "percentOfAdult": percent, "expectedAdultWeightRawKg": raw, "expectedAdultWeightKg": adult,
            "energyFactor": factor, "largeBreedCalcium": adult >= p("largeBreedAdultWeightKg"), "outOfRange": out_of_range}

def energy_details(request: AnalysisRequest, profile: StandardProfile | None = None, p: ParamTracker | None = None) -> dict:
    profile = profile_of(request, profile)
    p = p or ParamTracker(profile)
    species = request.profile.species.value
    stage = life_stage(request, profile, p)
    growth = None
    exponent = p(f"metabolicExponent{species.title()}")
    if profile.model == "FEDIAF_2025":
        if species == "DOG":
            factor = p("energyKDogAdult")
            if stage != "ADULT":
                # FEDIAF 2025 Table VII-8b, 8주~1년: 예상 성체 체중 비율을 사용한다.
                factor = p("puppyEnergyIntercept") - p("puppyEnergySlope") * (request.profile.weightKg / request.profile.expectedAdultWeightKg)
        else:
            factor = p("energyKCatAdult") if stage == "ADULT" else p("energyKCatGrowth")
    elif stage.startswith("DOG_GROWTH"):
        growth = _puppy_growth(request, p)
        factor = growth["energyFactor"]
    elif stage.startswith("CAT_GROWTH_"):
        factor = p(f"energyKCatGrowth{stage[-1]}")
    else:
        factor = p("energyKDogAdult" if species == "DOG" else "energyKCatAdult")
    return {"stage": stage, "factor": factor, "kcal": factor * request.profile.weightKg ** exponent, "growth": growth}

def energy(request: AnalysisRequest, profile: StandardProfile | None = None) -> float:
    return energy_details(request, profile)["kcal"]

def _reference(record, unit: str, applied: bool, footnote: str | None = None) -> dict:
    return {"sourceDocument": record.sourceDocument, "sourceTable": record.sourceTable,
            "sourceFootnote": footnote or record.sourceFootnote, "unit": unit, "basis": record.basis,
            "verificationStatus": record.verificationStatus.value,
            "verifiedAt": record.verifiedAt.isoformat() if record.verifiedAt else None, "appliedInJudgement": applied}

def standard_lines(request: AnalysisRequest, kcal: float, profile: StandardProfile, p: ParamTracker, stage: str,
                   growth: dict | None = None) -> tuple[dict, dict, set]:
    """성분별 하루 하한·주의·상한, 출처, 미검증으로 판정에서 뺀 성분 집합."""
    species = request.profile.species.value
    caution_ratio = p(f"cautionRatio{species.title()}")
    record_stage = profile.stage(species, stage).recordStage
    allow = allow_unverified_standards()
    lines, references, blocked = {}, {}, set()
    for item in profile.nutrients:
        record = profile.record(item.id, record_stage)
        minimum_ref, upper_ref = record.min, record.max
        # 기준표 대신 쓰는 각주 값(매개변수). 값은 레코드와 같은 단위다.
        overrides = []
        if profile.model == "FEDIAF_2025":
            # FEDIAF 2025 footnote b: 예상 성체 15 kg 초과인 개는 6개월까지 후기 성장기 칼슘 2.5 g/1000 kcal를 적용한다.
            if item.id == "CALCIUM" and species == "DOG" and stage == "GROWTH_LATE" \
                    and request.profile.expectedAdultWeightKg > p("largeBreedAdultWeightKg") \
                    and _fediaf_days(request, p) < p("largeBreedAgeDays"):
                minimum_ref, upper_ref = p("largeBreedCalciumMin"), p("largeBreedCalciumMax")
                overrides = ["largeBreedCalciumMin", "largeBreedCalciumMax"]
        elif item.id == "CALCIUM" and growth and growth["largeBreedCalcium"]:
            upper_ref = p("largeBreedCalciumMax")
            overrides = ["largeBreedCalciumMax"]
        factor = 1.0
        if record.unit != item.unit:
            factor = conversion_factor(record.unit, item.unit, master(profile.id, item.id))
        minimum = kcal / 1000 * (minimum_ref * factor if factor != 1.0 else minimum_ref) if minimum_ref is not None else None
        upper = kcal / 1000 * (upper_ref * factor if factor != 1.0 else upper_ref) if upper_ref is not None else None
        caution = upper * caution_ratio if upper else None
        unverified = record.verificationStatus == VerificationStatus.UNVERIFIED or any(
            profile.params[name].verificationStatus == VerificationStatus.UNVERIFIED for name in overrides)
        footnote = profile.params[overrides[0]].source if overrides else None
        applied = not unverified or allow
        if not applied:
            minimum = caution = upper = None
            blocked.add(item.id)
        lines[item.id] = {"minimum": minimum, "caution": caution, "upper": upper}
        references[item.id] = _reference(record, item.unit, applied, footnote)
    return lines, references, blocked

def thresholds(request: AnalysisRequest, kcal: float, profile: StandardProfile | None = None) -> dict:
    profile = profile_of(request, profile)
    p = ParamTracker(profile)
    details = energy_details(request, profile, p)
    return standard_lines(request, kcal, profile, p, details["stage"], details["growth"])[0]

def product_amounts(product: dict, profile: StandardProfile, warnings: list[str] | None = None) -> dict[str, float]:
    """제품 1회 제공량 함량을 프로필 단위로 바꾼다. 같은 단위면 값을 그대로 쓴다."""
    units, result = nutrient_units(profile), {}
    for nutrient_id, value in product["nutrients"].items():
        if nutrient_id not in units:
            if warnings is not None:
                warnings.append(f"{product['name']}의 {nutrient_id}는 {profile.displayName} 성분에 대응하지 않아 제외했습니다.")
            continue
        source = NUTRIENT_CATALOG.catalogUnits[nutrient_id]
        if source == units[nutrient_id]:
            result[nutrient_id] = value
            continue
        factor = conversion_factor(source, units[nutrient_id], master(profile.id, nutrient_id))
        if factor is None:
            if warnings is not None:
                warnings.append(f"{product['name']}의 {nutrient_id}({source})는 {units[nutrient_id]}로 환산할 근거가 없어 제외했습니다.")
            continue
        result[nutrient_id] = value * factor
    return result

def aggregate(request: AnalysisRequest, limits: dict, profile: StandardProfile | None = None) -> tuple[list[dict], bool, list[str], list[dict]]:
    profile = profile_of(request, profile)
    units = nutrient_units(profile)
    nutrient_index = {nutrient_id: index for index, nutrient_id in enumerate(units)}
    # 성분 ID를 고정 인덱스로 바꿔 모든 합산에서 같은 순서와 O(1) 위치 접근을 보장한다.
    totals = [{"fromFeed":0.0,"fromSupplements":0.0,"source":"ACTUAL"} for _ in units]
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
                values = {}
                for nutrient_id, line in limits.items():
                    if line["minimum"] is not None:
                        nutrient_total = totals[nutrient_index[nutrient_id]]
                        nutrient_total[bucket] += line["minimum"]
                        nutrient_total["source"] = "ESTIMATED"
                        values[nutrient_id] = line["minimum"]
                if values or profile.model == "FEDIAF_2025":
                    estimated = True
                    contributions.append({"name":product["name"],"type":"FEED","source":"ESTIMATED","nutrients":values})
                    warnings.append("사료 상세 성분이 없어 최소 권장량으로 추정했습니다.")
                else:
                    contributions.append({"name":product["name"],"type":"FEED","source":"UNKNOWN","nutrients":{}})
                    warnings.append("적용 가능한 최소 권장량이 없어 사료 몫을 추정하지 못했습니다(0으로 계산).")
            else:
                contributions.append({"name":product["name"],"type":"FEED","source":"ACTUAL","nutrients":{}})
                warnings.append("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.")
        else:
            values = {}
            for nutrient_id, value in product_amounts(product, profile, warnings).items():
                applied = value * ratio
                totals[nutrient_index[nutrient_id]][bucket] += applied
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
            unit = units.get(nutrient.nutrientId)
            if not unit:
                raise AnalysisError(f"지원하지 않는 성분입니다: {nutrient.nutrientId}")
            if profile.model == "FEDIAF_2025":
                applied = convert(nutrient.amount, nutrient.unit, unit) * ratio
            else:
                factor = conversion_factor(nutrient.unit.upper(), unit, master(profile.id, nutrient.nutrientId))
                if factor is None:
                    raise AnalysisError(f"변환할 수 없는 단위입니다: {nutrient.unit.upper()} → {unit}")
                applied = nutrient.amount * factor * ratio
            totals[nutrient_index[nutrient.nutrientId]][bucket] += applied
            values[nutrient.nutrientId] = applied
        contributions.append({"name":item.name,"type":item.type.value,"source":"ACTUAL","nutrients":values})
    return totals, estimated, warnings, contributions

def classify(total: float, line: dict) -> str:
    if line.get("unverified"): return UNVERIFIED_STANDARD
    if line["minimum"] is None and line["upper"] is None: return "NO_STANDARD"
    if line["minimum"] is not None and total < line["minimum"]: return "DEFICIENT"
    if line["upper"] is not None and total > line["upper"]: return "EXCESS"
    if line["caution"] is not None and total >= line["caution"]: return "CAUTION"
    return "ADEQUATE" if line["upper"] is not None else "ADEQUATE_NO_UPPER_LIMIT"

def calcium_phosphorus_status(request: AnalysisRequest, calcium: float, phosphorus: float, profile: StandardProfile | None = None,
                              p: ParamTracker | None = None) -> str:
    profile = profile_of(request, profile)
    p = p or ParamTracker(profile)
    if profile.model != "FEDIAF_2025":
        bounds = ("caPMin", "caPMax")
        if not allow_unverified_standards() and any(profile.params[name].verificationStatus == VerificationStatus.UNVERIFIED for name in bounds):
            p.used.update({name: profile.params[name] for name in bounds})
            return UNVERIFIED_STANDARD
        if not phosphorus:
            return "UNAVAILABLE"
        value = calcium / phosphorus
        return "LOW" if value < p("caPMin") else "HIGH" if value > p("caPMax") else "ADEQUATE"
    if not phosphorus:
        return "UNAVAILABLE"
    stage, value = life_stage(request, profile, p), calcium / phosphorus
    if stage == "GROWTH_EARLY": upper = p("caPUpperGrowthEarly")
    elif stage == "GROWTH_LATE": upper = p("caPUpperGrowthLate") if request.profile.expectedAdultWeightKg <= p("largeBreedAdultWeightKg") or _fediaf_days(request, p) >= p("largeBreedAgeDays") else p("caPUpperGrowthLateLargeBreed")
    else: upper = p("caPUpperAdult")
    return "LOW" if value < p("caPMin") else "HIGH" if value > upper else "ADEQUATE"

def standard_block(profile: StandardProfile, references: dict, p: ParamTracker) -> dict:
    statuses = [reference["verificationStatus"] for reference in references.values()]
    unverified_params = sorted(name for name, param in p.used.items() if param.verificationStatus == VerificationStatus.UNVERIFIED)
    allow = allow_unverified_standards()
    warnings = []
    if "UNVERIFIED" in statuses or unverified_params:
        warnings.append(f"미검증 프로필: {profile.displayName}에는 원문 대조 전 기준값·매개변수가 포함되어 있습니다.")
    if "UNVERIFIED" in statuses and not allow:
        warnings.append("원문 대조 전 기준값은 판정과 추천 점수에 쓰지 않았습니다(UNVERIFIED_STANDARD).")
    if allow:
        warnings.append("ALLOW_UNVERIFIED_STANDARDS가 켜져 있어 미검증 기준값을 판정에 사용했습니다. 테스트·개발용 결과입니다.")
    return {"id": profile.id.value, "displayName": profile.displayName, "version": profile.version,
            "documentName": profile.documentName, "publisher": profile.publisher, "basis": profile.basis,
            "allowUnverifiedStandards": allow,
            "verification": {"verified": statuses.count("VERIFIED"), "unverified": statuses.count("UNVERIFIED"),
                             "notApplicable": statuses.count("NOT_APPLICABLE"), "unverifiedParameters": unverified_params},
            "warnings": warnings}

def analyze(request: AnalysisRequest) -> dict:
    profile = profile_of(request)
    p = ParamTracker(profile)
    details = energy_details(request, profile, p)
    kcal, stage = details["kcal"], details["stage"]
    limits, references, blocked = standard_lines(request, kcal, profile, p, stage, details["growth"])
    totals, estimated, warnings, contributions = aggregate(request, limits, profile)
    legacy = profile.model == "FEDIAF_2025"
    nutrients, summary = [], {"deficient":0,"adequate":0,"caution":0,"excess":0,"noStandard":0}
    if not legacy:
        summary["unverifiedStandard"] = 0
    for index, item in enumerate(profile.nutrients):
        parts = totals[index]
        total = parts["fromFeed"] + parts["fromSupplements"]
        status = classify(total, {**limits[item.id], "unverified": item.id in blocked})
        summary[{"NO_STANDARD":"noStandard","DEFICIENT":"deficient","CAUTION":"caution","EXCESS":"excess",UNVERIFIED_STANDARD:"unverifiedStandard"}.get(status,"adequate")] += 1
        nutrients.append({"nutrientId":item.id,"name":master(profile.id, item.id).nameKo,"unit":item.unit,**parts,"total":total,**limits[item.id],"status":status,"reference":references[item.id]})
    ratios = {}
    if request.profile.species.value == "DOG":
        calcium = next(x["total"] for x in nutrients if x["nutrientId"] == "CALCIUM")
        phosphorus = next(x["total"] for x in nutrients if x["nutrientId"] == "PHOSPHORUS")
        value = calcium / phosphorus if phosphorus else None
        ratios["calciumPhosphorus"] = {"value":value,"status":calcium_phosphorus_status(request, calcium, phosphorus, profile, p)}
    fingerprint = sha256(request.model_dump_json().encode()).hexdigest()[:16]
    standard = standard_block(profile, references, p)
    caution_percent = round(profile.param(f"cautionRatio{request.profile.species.value.title()}") * 100)
    if legacy:
        result_warnings=warnings+["비타민 E와 오메가3는 현재 제품 단위가 공식 기준과 달라 기준 없음으로 표시하며 추천 점수에서 제외합니다.","상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 공식 상한을 적용하지 않았다는 뜻입니다."]
        caution_policy = f"서비스 조기 경고 기준: 공식 상한의 {caution_percent}%부터 주의"
    else:
        result_warnings = warnings + standard["warnings"] + ["상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 상한을 적용하지 않았다는 뜻입니다.", "임신·수유 중인 개체는 지원하지 않습니다."]
        if details["growth"] and details["growth"]["outOfRange"]:
            result_warnings.insert(0, "예상 성체 체중이 선택한 체급 범위를 벗어났습니다. 체급 또는 체중을 확인하세요.")
        caution_policy = f"서비스 조기 경고 기준: 상한의 {caution_percent}%부터 주의"
    result = {"traceId":f"{fingerprint}-{uuid4().hex[:8]}","standardVersion":profile.version,"standardSource":profile.sourceText,"cautionPolicy":caution_policy,"lifeStage":stage,"referenceEnergyKcal":kcal,"usesEstimatedFeed":estimated,"summary":summary,"nutrients":nutrients,"contributions":contributions,"ratios":ratios,"warnings":result_warnings}
    if not legacy:
        result["energyFactor"] = details["factor"]
        result["growth"] = details["growth"]
    result["standard"] = standard
    return result

def recommend(request: RecommendationRequest) -> dict:
    profile = profile_of(request)
    base = analyze(request)
    names = {item.id: master(profile.id, item.id).nameKo for item in profile.nutrients}
    current = {x["nutrientId"]:x["total"] for x in base["nutrients"]}
    limits = {x["nutrientId"]:{"minimum":x["minimum"],"caution":x["caution"],"upper":x["upper"],"unverified":x["status"] == UNVERIFIED_STANDARD} for x in base["nutrients"]}
    original = {x["nutrientId"]:x["status"] for x in base["nutrients"]}
    selected, excluded = [], []
    remaining = []
    for p in PRODUCTS:
        if p["type"] == "SUPPLEMENT" and p.get("recommendedDailyAmount"):
            amounts = product_amounts(p, profile)
            if amounts:
                remaining.append({**p, "nutrients": amounts})
    class MaxHeap:
        def __init__(self): self.values = []
        def push(self, value):
            self.values.append(value); index = len(self.values) - 1
            while index:
                parent = (index - 1) // 2
                if self.values[parent][:4] >= value[:4]: break
                self.values[parent], self.values[index] = self.values[index], self.values[parent]
                index = parent
        def pop(self):
            if not self.values: return None
            top, last = self.values[0], self.values.pop()
            if self.values:
                self.values[0] = last; index = 0
                while True:
                    left, right = index * 2 + 1, index * 2 + 2
                    if left >= len(self.values): break
                    best = right if right < len(self.values) and self.values[right][:4] > self.values[left][:4] else left
                    if self.values[index][:4] >= self.values[best][:4]: break
                    self.values[index], self.values[best] = self.values[best], self.values[index]
                    index = best
            return top

    for _ in range(request.maxItems):
        safe = MaxHeap()
        for product in remaining:
            ratio = product["recommendedDailyAmount"] / product["servingAmount"]
            projected = deepcopy(current)
            for key, value in product["nutrients"].items(): projected[key] += value * ratio
            statuses = {key:classify(value, limits[key]) for key,value in projected.items()}
            harmful = [names[key] for key,status in statuses.items() if key in product["nutrients"] and status in {"CAUTION","EXCESS"}]
            if request.profile.species.value == "DOG" and ({"CALCIUM", "PHOSPHORUS"} & product["nutrients"].keys()):
                ratio_status = calcium_phosphorus_status(request, projected["CALCIUM"], projected["PHOSPHORUS"], profile)
                if ratio_status in {"LOW", "HIGH"}: harmful.append("칼슘:인 비율")
            if harmful:
                excluded.append({"productId":product["id"],"name":product["name"],"reason":f"추가 후 기준 이탈 예상: {', '.join(harmful)}"})
                continue
            fixed = sum(1 for key,status in original.items() if status == "DEFICIENT" and statuses[key] != "DEFICIENT")
            # 미검증 기준 성분은 추천 점수에서 뺀다.
            overlap = sum(1 for key in product["nutrients"] if original.get(key) not in {"DEFICIENT","NO_STANDARD",UNVERIFIED_STANDARD})
            margins = [(limits[k]["upper"]-projected[k])/(limits[k]["upper"]-limits[k]["minimum"]) for k in product["nutrients"] if limits[k]["upper"] and limits[k]["minimum"] and limits[k]["upper"] > limits[k]["minimum"]]
            margin = max(0.0, min(1.0, sum(margins)/len(margins))) if margins else 0.0
            score = 10 * fixed + 5 * margin - overlap
            safe.push((score,fixed,-len(product["nutrients"]),product["name"],product,projected,statuses))
        best = safe.pop()
        if best is None: break
        _, fixed, _, _, product, current, statuses = best
        selected.append({"productId":product["id"],"name":product["name"],"dailyAmount":product["recommendedDailyAmount"],"unit":product["servingUnit"],"score":round(best[0],3),"fixedNutrients":fixed,"projectedStatuses":statuses})
        remaining = [p for p in remaining if p["id"] != product["id"]]
        original = statuses
    return {"traceId":base["traceId"],"standardVersion":profile.version,"message":"적용 가능한 기준에서 주의·과다가 검출되지 않은 후보입니다." if base["summary"]["deficient"] == 0 else "부족 성분, 기준선까지의 여유, 중복 성분을 함께 고려했습니다.","items":selected,"excluded":list({x["productId"]:x for x in excluded}.values()),"usesEstimatedFeed":base["usesEstimatedFeed"],"standard":base["standard"]}
