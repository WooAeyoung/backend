"""분석 API 서버 (FastAPI + Uvicorn).

F-027: 분석 API — 영양소 합산·상태 판정·제품별 기여도를 JSON 으로 반환한다.
로컬 SQLite(db/wooaeyoung.db)와 CSV 데모 데이터를 함께 사용할 수 있다.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
from pathlib import Path
from typing import Any

import pandas as pd
import uvicorn
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    Request,
    UploadFile,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from backend import auth as auth_mod
from backend.nutrients import normalize_nutrient_name
from backend.pricing import best_offer
from backend.recommend import recommend_swaps
from backend.units import UnitContext, UnitError, supported_units, to_mg_per_serving

from backend.database import (
    DB_PATH,
    analyze,
    create_pet,
    get_feeding_plan,
    get_pet,
    get_product,
    init_db,
    list_pets,
    list_products,
    product_contribution_list,
    products_to_dataframe,
    set_feeding_plan,
    upsert_product,
)
from backend.label_ocr import (
    CATEGORIES,
    DraftNutrient,
    LabelDraft,
    draft_to_rows,
    extract_text_from_image,
    parse_label_text,
)
from backend.nutrition import (
    FeedingSelection,
    calculate_intake,
    product_contributions,
    summarize_intake,
)

APP = FastAPI(title="우애영 Analysis API", version="0.2.0")
PUBLIC_SERVER = os.environ.get("WOOAEYOUNG_PUBLIC_SERVER", "0") == "1"

APP.add_middleware(
    CORSMiddleware,
    allow_origins=(
        [s.strip() for s in os.environ.get("WOOAEYOUNG_CORS_ORIGINS", "").split(",") if s.strip()]
        if PUBLIC_SERVER else ["*"]
    ),
    allow_methods=["*"],
    allow_headers=["*"],
)


@APP.middleware("http")
async def public_server_boundary(request: Request, call_next):
    if PUBLIC_SERVER:
        path = request.url.path.rstrip("/")
        # Desktop diagnostics accept local paths and expose shared records.
        # The web UI uses catalog and authenticated session APIs instead.
        if "db" in request.query_params or "standards_csv" in request.query_params:
            return JSONResponse({"detail": "공개 서버에서는 파일 경로를 지정할 수 없습니다."}, status_code=400)
        if (
            path == "/api/products" or path.startswith("/api/products/")
            or path in ("/api/analyze", "/api/contributions")
            or path.startswith("/api/pets/")
            or (path == "/api/pets" and request.method != "GET")
        ):
            return JSONResponse({"detail": "공개 서버에서 제공하지 않는 로컬 전용 API입니다."}, status_code=404)
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    if PUBLIC_SERVER and request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


@APP.on_event("startup")
def _ensure_schema() -> None:
    """새 설치·빈 DB 에서도 저장/복원이 바로 동작하도록 스키마를 보장한다."""
    try:
        init_db()
        from backend.shop import seed_reviews_if_empty

        seed_reviews_if_empty()
    except Exception as exc:  # pragma: no cover - 기동 로그용
        if PUBLIC_SERVER:
            raise
        print(f"[startup] init_db skipped: {exc}")

ROOT = Path(__file__).resolve().parent.parent
# PyInstaller 로 동결하면 읽기 전용 자산은 _MEIPASS 아래에 풀린다.
BUNDLE = Path(getattr(sys, "_MEIPASS", ROOT))
# 저장 DB 는 database 모듈과 같은 경로를 쓴다(WOOAEYOUNG_DB 우선). 두 곳이 갈리면 안 된다.
DEFAULT_DB = DB_PATH
PROCESSED = BUNDLE / "data" / "processed"
FRONTEND_DIST = BUNDLE / "frontend" / "dist"
PRODUCT_SCHEMA = {
    "type": "object",
    "properties": {
        "product_id": {"type": "string"},
        "name": {"type": "string"},
        "category": {"type": "string", "enum": ["주식", "간식", "영양제"]},
        "serving_basis_g": {"type": "number", "exclusiveMinimum": 0},
        "monthly_price_krw": {"type": "integer", "minimum": 0},
        "nutrients": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "nutrient": {"type": "string"},
                    "amount_mg": {"type": "number", "minimum": 0},
                    "label_complete": {"type": "boolean"},
                },
                "required": ["nutrient", "amount_mg"],
            },
        },
    },
    "required": ["product_id", "name", "category", "serving_basis_g"],
}


def _db_path_from_query(db: str | None) -> Path:
    if db:
        if PUBLIC_SERVER:
            raise HTTPException(400, "공개 서버에서는 파일 경로를 지정할 수 없습니다.")
        return Path(db).expanduser().resolve()
    return DEFAULT_DB


# ---------------------------------------------------------------------------
# 인증 (F-028) — Authorization: Bearer <token>
# ---------------------------------------------------------------------------
def _bearer(authorization: str | None) -> str:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return ""


def current_user(
    authorization: str | None = Header(default=None),
    db: str | None = Query(None),
) -> dict[str, Any]:
    """로그인 필수 엔드포인트용. 토큰이 없거나 만료면 401."""
    user = auth_mod.user_for_token(_bearer(authorization), db_path=_db_path_from_query(db))
    if user is None:
        raise HTTPException(401, "로그인이 필요합니다.")
    return user


def optional_user(
    authorization: str | None = Header(default=None),
    db: str | None = Query(None),
) -> dict[str, Any] | None:
    user = auth_mod.user_for_token(_bearer(authorization), db_path=_db_path_from_query(db))
    if PUBLIC_SERVER and user is None:
        raise HTTPException(401, "로그인이 필요합니다.")
    return user


class RegisterRequest(BaseModel):
    email: str
    password: str
    display_name: str | None = None


class LoginRequest(BaseModel):
    email: str
    password: str


@APP.post("/api/auth/register", status_code=201)
def api_register(req: RegisterRequest, db: str | None = Query(None)):
    try:
        user = auth_mod.register(
            req.email, req.password, req.display_name, db_path=_db_path_from_query(db)
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    session = auth_mod.login(req.email, req.password, db_path=_db_path_from_query(db))
    return {"user": user, "token": session["token"]}


@APP.post("/api/auth/login")
def api_login(req: LoginRequest, db: str | None = Query(None)):
    try:
        return auth_mod.login(req.email, req.password, db_path=_db_path_from_query(db))
    except ValueError as exc:
        raise HTTPException(401, str(exc))


@APP.post("/api/auth/logout")
def api_logout(authorization: str | None = Header(default=None), db: str | None = Query(None)):
    auth_mod.logout(_bearer(authorization), db_path=_db_path_from_query(db))
    return {"ok": True}


@APP.get("/api/auth/me")
def api_me(user: dict[str, Any] = Depends(current_user)):
    return user


# ---------------------------------------------------------------------------
# 펫 관련 엔드포인트
# ---------------------------------------------------------------------------
@APP.get("/api/pets")
def api_list_pets(
    db: str | None = Query(None, description="SQLite 경로"),
    user: dict[str, Any] | None = Depends(optional_user),
):
    pets = list_pets(
        db_path=_db_path_from_query(db),
        user_id=user["user_id"] if user else None,
    )
    if PUBLIC_SERVER:
        return [pet for pet in pets if pet.get("user_id") == user["user_id"]]
    return pets


@APP.post("/api/pets", status_code=201)
def api_create_pet(
    name: str,
    species: str = "dog",
    weight_kg: float = 8.0,
    life_stage: str = "adult",
    birth_date: str | None = None,
    breed: str | None = None,
    neutered: bool = False,
    db: str | None = Query(None),
):
    if species not in ("dog", "cat"):
        raise HTTPException(400, "species는 dog 또는 cat이어야 합니다")
    if weight_kg <= 0:
        raise HTTPException(400, "weight_kg는 0보다 커야 합니다")
    pet_id = create_pet(
        name=name,
        species=species,
        weight_kg=weight_kg,
        life_stage=life_stage,
        birth_date=birth_date,
        breed=breed,
        neutered=neutered,
        db_path=_db_path_from_query(db),
    )
    return {"pet_id": pet_id}


@APP.get("/api/pets/{pet_id}")
def api_get_pet(pet_id: int, db: str | None = Query(None)):
    pet = get_pet(pet_id, db_path=_db_path_from_query(db))
    if pet is None:
        raise HTTPException(404, "펫을 찾을 수 없습니다")
    return pet


# ---------------------------------------------------------------------------
# 제품 관련 엔드포인트
# ---------------------------------------------------------------------------
@APP.post("/api/products")
def api_upsert_product(
    product_id: str,
    name: str,
    category: str,
    serving_basis_g: float,
    monthly_price_krw: int | None = None,
    nutrients: list[dict] | None = None,
    db: str | None = Query(None),
):
    if category not in ("주식", "간식", "영양제"):
        raise HTTPException(400, "category는 주식/간식/영양제 중 하나여야 합니다")
    if serving_basis_g <= 0:
        raise HTTPException(400, "serving_basis_g는 0보다 커야 합니다")
    if nutrients is None:
        nutrients = []
    clean_nutrients: list[tuple[str, float, bool]] = []
    seen: set[str] = set()
    for n in nutrients:
        if not isinstance(n, dict):
            continue
        nut = str(n.get("nutrient", "")).strip()
        amt = float(n.get("amount_mg", 0))
        if not nut or amt < 0:
            continue
        if nut in seen:
            continue
        seen.add(nut)
        clean_nutrients.append((nut, amt, bool(n.get("label_complete", True))))
    upsert_product(
        product_id=product_id,
        name=name,
        category=category,
        serving_basis_g=serving_basis_g,
        monthly_price_krw=monthly_price_krw,
        source="api",
        label_complete=True,
        nutrients=clean_nutrients,
        db_path=_db_path_from_query(db),
    )
    return {"product_id": product_id, "ok": True}


@APP.get("/api/products")
def api_list_products(db: str | None = Query(None)):
    return list_products(db_path=_db_path_from_query(db))


@APP.get("/api/products/{product_id}")
def api_get_product(product_id: str, db: str | None = Query(None)):
    p = get_product(product_id, db_path=_db_path_from_query(db))
    if p is None:
        raise HTTPException(404, "제품을 찾을 수 없습니다")
    return p


# ---------------------------------------------------------------------------
# 식단(급여 계획) 엔드포인트
# ---------------------------------------------------------------------------
@APP.post("/api/pets/{pet_id}/feeding")
def api_set_feeding(
    pet_id: int,
    product_id: str,
    daily_amount_g: float,
    active: bool = True,
    db: str | None = Query(None),
):
    if daily_amount_g < 0:
        raise HTTPException(400, "daily_amount_g는 음수가 될 수 없습니다")
    # 존재 확인
    pet = get_pet(pet_id, db_path=_db_path_from_query(db))
    if pet is None:
        raise HTTPException(404, "펫을 찾을 수 없습니다")
    prod = get_product(product_id, db_path=_db_path_from_query(db))
    if prod is None:
        raise HTTPException(404, "제품을 찾을 수 없습니다")
    set_feeding_plan(
        pet_id=pet_id,
        product_id=product_id,
        daily_amount_g=daily_amount_g,
        active=active,
        db_path=_db_path_from_query(db),
    )
    return {"pet_id": pet_id, "product_id": product_id, "active": active}


@APP.get("/api/pets/{pet_id}/feeding")
def api_get_feeding(pet_id: int, db: str | None = Query(None)):
    pet = get_pet(pet_id, db_path=_db_path_from_query(db))
    if pet is None:
        raise HTTPException(404, "펫을 찾을 수 없습니다")
    return get_feeding_plan(pet_id, db_path=_db_path_from_query(db))


# ---------------------------------------------------------------------------
# 분석 엔드포인트
# ---------------------------------------------------------------------------
@APP.get("/api/analyze")
def api_analyze(
    pet_id: int,
    db: str | None = Query(None, description="SQLite 경로"),
    standards_csv: str | None = Query(None, description="nutrient_standards.csv 경로"),
):
    db_path = _db_path_from_query(db)
    pet = get_pet(pet_id, db_path=db_path)
    if pet is None:
        raise HTTPException(404, "펫을 찾을 수 없습니다")
    products = list_products(db_path=db_path)
    if not products:
        raise HTTPException(400, "저장된 제품이 없습니다")
    plan = get_feeding_plan(pet_id, db_path=db_path)

    # CSV 기준표가 있으면 쓰고, 없으면 내부 기본값을 사용한다
    if standards_csv:
        std_df = pd.read_csv(Path(standards_csv).expanduser())
    else:
        std_path = ROOT / "data" / "processed" / "nutrient_standards.csv"
        if std_path.exists():
            std_df = pd.read_csv(std_path)
        else:
            std_df = pd.DataFrame([
                {"nutrient": "칼슘", "demo_min_mg": 1000, "demo_max_mg": 1900},
                {"nutrient": "인", "demo_min_mg": 750, "demo_max_mg": 1500},
                {"nutrient": "비타민D", "demo_min_mg": 0.015, "demo_max_mg": 0.035},
                {"nutrient": "아연", "demo_min_mg": 14, "demo_max_mg": 35},
                {"nutrient": "철", "demo_min_mg": 8, "demo_max_mg": 24},
            ])

    selections = [
        FeedingSelection(p["product_id"], p["daily_amount_g"], p["active"])
        for p in plan
    ]
    result = analyze(products, selections, std_df)
    return result


@APP.get("/api/contributions")
def api_contributions(
    pet_id: int,
    nutrient: str,
    db: str | None = Query(None),
    standards_csv: str | None = Query(None),
):
    db_path = _db_path_from_query(db)
    pet = get_pet(pet_id, db_path=db_path)
    if pet is None:
        raise HTTPException(404, "펫을 찾을 수 없습니다")
    products = list_products(db_path=db_path)
    if not products:
        raise HTTPException(400, "저장된 제품이 없습니다")
    plan = get_feeding_plan(pet_id, db_path=db_path)

    if standards_csv:
        std_df = pd.read_csv(Path(standards_csv).expanduser())
    else:
        std_path = ROOT / "data" / "processed" / "nutrient_standards.csv"
        if std_path.exists():
            std_df = pd.read_csv(std_path)
        else:
            std_df = pd.DataFrame([{"nutrient": nutrient, "demo_min_mg": 0, "demo_max_mg": 1}])

    selections = [
        FeedingSelection(p["product_id"], p["daily_amount_g"], p["active"])
        for p in plan
    ]
    contrib = product_contribution_list(products, selections, nutrient)
    if not contrib:
        return {"nutrient": nutrient, "contributions": [], "total_mg": 0}
    total = sum(c["daily_nutrient_mg"] for c in contrib)
    return {"nutrient": nutrient, "contributions": contrib, "total_mg": total}


@APP.get("/health")
def health():
    return {"status": "ok"}


# ===========================================================================
# 세션(비저장) 분석 · 카탈로그 · OCR — React 클라이언트 전용 (F-002/005/006/008/
# 010/012/013/015/016/017/018/020)
# ===========================================================================
def _standards_df() -> pd.DataFrame:
    path = PROCESSED / "nutrient_standards.csv"
    if path.exists():
        return pd.read_csv(path)
    return pd.DataFrame(
        [
            {"nutrient": "칼슘", "demo_min_mg": 1000, "demo_max_mg": 1900},
            {"nutrient": "인", "demo_min_mg": 750, "demo_max_mg": 1500},
            {"nutrient": "비타민D", "demo_min_mg": 0.015, "demo_max_mg": 0.035},
            {"nutrient": "아연", "demo_min_mg": 14, "demo_max_mg": 35},
            {"nutrient": "철", "demo_min_mg": 8, "demo_max_mg": 24},
        ]
    )


def _catalog_products() -> list[dict]:
    """data/processed/products.csv 를 제품 단위(내부 nutrients 배열)로 묶는다."""
    path = PROCESSED / "products.csv"
    if not path.exists():
        return []
    df = pd.read_csv(path)
    out: list[dict] = []
    has_brand = "brand" in df.columns
    has_source = "source" in df.columns
    for pid, rows in df.groupby("product_id", sort=False):
        head = rows.iloc[0]
        out.append(
            {
                "product_id": str(pid),
                "name": str(head["product_name"]),
                "brand": str(head["brand"]) if has_brand and not pd.isna(head["brand"]) else None,
                "category": str(head["category"]),
                "serving_basis_g": float(head["serving_basis_g"]),
                "monthly_price_krw": int(head["monthly_price_krw"])
                if not pd.isna(head["monthly_price_krw"])
                else 0,
                "label_complete": bool(head["label_complete"]),
                "source": str(head["source"]) if has_source and not pd.isna(head["source"]) else "demo",
                "nutrients": [
                    {
                        "nutrient": normalize_nutrient_name(str(r["nutrient"])),
                        "amount_mg": float(r["amount_mg"]),
                        "label_complete": bool(r["label_complete"]),
                    }
                    for _, r in rows.iterrows()
                    if str(r["nutrient"]).strip()
                ],
            }
        )
    return out


@APP.get("/api/catalog/products")
def api_catalog_products():
    return _catalog_products()


@APP.get("/api/catalog/standards")
def api_catalog_standards():
    return _standards_df().to_dict(orient="records")


class NutrientIn(BaseModel):
    nutrient: str
    amount_mg: float = Field(ge=0)
    label_complete: bool = True


class ProductIn(BaseModel):
    product_id: str
    name: str
    brand: str | None = None
    category: str
    serving_basis_g: float = Field(gt=0)
    monthly_price_krw: int = 0
    label_complete: bool = True
    nutrients: list[NutrientIn] = []


class SelectionIn(BaseModel):
    product_id: str
    daily_amount_g: float = Field(ge=0)
    active: bool = True


class SessionAnalyzeRequest(BaseModel):
    products: list[ProductIn]
    selections: list[SelectionIn]


def _normalize_products(products: list[dict]) -> list[dict]:
    """들어온 제품의 성분명을 표준명으로 접는다(F-032)."""
    for p in products:
        for n in p.get("nutrients", []):
            n["nutrient"] = normalize_nutrient_name(n["nutrient"])
    return products


@APP.post("/api/session/analyze")
def api_session_analyze(req: SessionAnalyzeRequest):
    """DB 저장 없이 현재 화면 조합을 분석한다. 영양소 요약 + 제품별 기여도."""
    products = _normalize_products([p.model_dump() for p in req.products])
    if not products:
        raise HTTPException(400, "제품이 하나도 없습니다")
    products_df = products_to_dataframe(products)
    selections = [
        FeedingSelection(s.product_id, s.daily_amount_g, s.active)
        for s in req.selections
    ]
    try:
        intake = calculate_intake(products_df, selections)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    summary = summarize_intake(intake, _standards_df())

    contributions: dict[str, list[dict]] = {}
    for nutrient in summary["nutrient"].tolist():
        contributions[nutrient] = product_contributions(intake, nutrient).to_dict(
            orient="records"
        )
    return {
        "summary": summary.to_dict(orient="records"),
        "intake": intake[
            ["nutrient", "product_id", "product_name", "daily_nutrient_mg", "label_complete"]
        ].to_dict(orient="records")
        if not intake.empty
        else [],
        "contributions": contributions,
    }


def _draft_payload(draft: LabelDraft, raw_text: str = "") -> dict:
    return {
        "product_name": draft.product_name,
        "category": draft.category,
        "serving_basis_g": draft.serving_basis_g,
        "label_complete": draft.label_complete,
        "nutrients": [
            {
                "nutrient": n.nutrient,
                "amount_mg": n.amount_mg,
                "source_text": n.source_text,
                "source_unit": n.source_unit,
            }
            for n in draft.nutrients
        ],
        "unparsed_lines": draft.unparsed_lines,
        "categories": CATEGORIES,
        "raw_text": raw_text,
    }


@APP.get("/api/ocr/available")
def api_ocr_available():
    """라벨 사진 판독 경로 가용성. 프론트가 촬영/업로드 UI 를 조절한다."""
    from backend.ocr import available as tess_available
    from backend.vision_ocr import available as vision_available

    return {"image_ocr": tess_available(), "vision": vision_available()}


@APP.post("/api/ocr/vision")
async def api_ocr_vision(
    file: UploadFile = File(...),
    user: dict[str, Any] | None = Depends(optional_user),
):
    """라벨 사진을 비전 AI 로 읽어 제품 초안을 만든다 (F-007). 자동 확정하지 않는다."""
    from backend.vision_ocr import read_label

    data = await file.read()
    if not data:
        raise HTTPException(400, "이미지가 비어 있습니다.")
    media = file.content_type or "image/jpeg"
    try:
        result = read_label(data, media_type=media)
    except RuntimeError as exc:
        raise HTTPException(422, str(exc))
    draft = LabelDraft(
        product_name=result["product_name"],
        category=result["category"],
        serving_basis_g=result["serving_basis_g"],
        label_complete=False,
        nutrients=[
            DraftNutrient(
                nutrient=normalize_nutrient_name(n["nutrient"]),
                amount_mg=float(n["amount_mg"]),
                source_text="vision",
                source_unit="mg",
            )
            for n in result["nutrients"]
        ],
    )
    payload = _draft_payload(draft, result.get("notes", ""))
    payload["engine"] = "vision"
    return payload


@APP.post("/api/ocr/draft")
async def api_ocr_draft(
    file: UploadFile | None = File(default=None),
    text: str = Form(default=""),
):
    """라벨 사진 또는 텍스트에서 제품 초안(후보값)을 만든다. 자동 확정하지 않는다. (F-006/F-007/F-008)

    사진이면 비전 AI 를 우선 쓰고, 자격증명이 없으면 Tesseract → 없으면 텍스트 유도.
    """
    from backend.ocr import image_to_text
    from backend.vision_ocr import available as vision_available, read_label

    raw = (text or "").strip()
    if not raw and file is not None:
        blob = await file.read()
        if vision_available():
            try:
                result = read_label(blob, media_type=file.content_type or "image/jpeg")
                draft = LabelDraft(
                    product_name=result["product_name"],
                    category=result["category"],
                    serving_basis_g=result["serving_basis_g"],
                    label_complete=False,
                    nutrients=[
                        DraftNutrient(
                            normalize_nutrient_name(n["nutrient"]),
                            float(n["amount_mg"]),
                            "vision",
                            "mg",
                        )
                        for n in result["nutrients"]
                    ],
                )
                payload = _draft_payload(draft, result.get("notes", ""))
                payload["engine"] = "vision"
                return payload
            except RuntimeError:
                pass  # Tesseract 로 폴백
        try:
            raw = image_to_text(blob)
        except RuntimeError as exc:
            raise HTTPException(422, str(exc))
    if not raw:
        raise HTTPException(400, "이미지에서 글자를 찾지 못했습니다. 라벨 텍스트를 붙여넣어 주세요.")
    payload = _draft_payload(parse_label_text(raw), raw)
    payload["engine"] = "tesseract" if not text.strip() else "text"
    return payload


class DraftConfirmRequest(BaseModel):
    product_name: str
    category: str
    serving_basis_g: float = Field(gt=0)
    label_complete: bool = False
    monthly_price_krw: int = 0
    nutrients: list[NutrientIn] = []


@APP.post("/api/ocr/confirm")
def api_ocr_confirm(req: DraftConfirmRequest):
    """사람이 확인·수정한 초안을 제품 1건(내부 nutrients 배열)으로 확정한다."""
    draft = LabelDraft(
        product_name=req.product_name,
        category=req.category,
        serving_basis_g=req.serving_basis_g,
        label_complete=req.label_complete,
        nutrients=[
            DraftNutrient(n.nutrient.strip(), float(n.amount_mg), "", "mg")
            for n in req.nutrients
            if n.nutrient.strip() and n.amount_mg > 0
        ],
    )
    try:
        rows = draft_to_rows(draft, monthly_price_krw=req.monthly_price_krw)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    head = rows.iloc[0]
    return {
        "product_id": str(head["product_id"]),
        "name": str(head["product_name"]),
        "category": str(head["category"]),
        "serving_basis_g": float(head["serving_basis_g"]),
        "monthly_price_krw": int(head["monthly_price_krw"]),
        "label_complete": bool(head["label_complete"]),
        "nutrients": [
            {
                "nutrient": str(r["nutrient"]),
                "amount_mg": float(r["amount_mg"]),
                "label_complete": bool(r["label_complete"]),
            }
            for _, r in rows.iterrows()
        ],
    }


class ProfileIn(BaseModel):
    name: str = "몽이"
    weight_kg: float = Field(gt=0, default=8.0)
    age: int = 5
    breed: str = ""
    neutered: bool = False


class SessionSaveRequest(BaseModel):
    profile: ProfileIn
    products: list[ProductIn]
    selections: list[SelectionIn]


@APP.post("/api/session/save", status_code=201)
def api_session_save(
    req: SessionSaveRequest,
    db: str | None = Query(None),
    user: dict[str, Any] | None = Depends(optional_user),
):
    """현재 화면 전체(프로필·제품·급여량)를 SQLite 에 한 번에 저장한다. (F-026)

    로그인 상태면 해당 사용자 소유로 저장된다(F-028).
    """
    db_path = _db_path_from_query(db)
    pet_id = create_pet(
        name=req.profile.name,
        species="dog",
        weight_kg=req.profile.weight_kg,
        life_stage="adult",
        breed=req.profile.breed or None,
        neutered=req.profile.neutered,
        user_id=user["user_id"] if user else None,
        db_path=db_path,
    )
    sel_by_id = {s.product_id: s for s in req.selections}
    saved = 0
    for p in req.products:
        s = sel_by_id.get(p.product_id)
        if s is None:
            continue
        stored_id = f"session:{pet_id}:{p.product_id}" if PUBLIC_SERVER else p.product_id
        upsert_product(
            product_id=stored_id,
            name=p.name,
            category=p.category,
            serving_basis_g=p.serving_basis_g,
            monthly_price_krw=p.monthly_price_krw,
            source="app_session",
            label_complete=p.label_complete,
            nutrients=[(n.nutrient, n.amount_mg, n.label_complete) for n in p.nutrients],
            db_path=db_path,
        )
        set_feeding_plan(
            pet_id=pet_id,
            product_id=stored_id,
            daily_amount_g=s.daily_amount_g,
            active=s.active,
            db_path=db_path,
        )
        saved += 1
    return {"pet_id": pet_id, "saved_products": saved}


@APP.get("/api/session/restore/{pet_id}")
def api_session_restore(
    pet_id: int,
    db: str | None = Query(None),
    user: dict[str, Any] | None = Depends(optional_user),
):
    """저장된 펫의 프로필·제품·급여량을 화면 복원용 형태로 돌려준다. (F-026)"""
    db_path = _db_path_from_query(db)
    pet = get_pet(
        pet_id, db_path=db_path, user_id=user["user_id"] if user else None
    )
    if pet is None or (PUBLIC_SERVER and pet.get("user_id") != user["user_id"]):
        raise HTTPException(404, "펫을 찾을 수 없습니다")
    plan = get_feeding_plan(pet_id, db_path=db_path)
    known = {p["product_id"]: p for p in list_products(db_path=db_path)}
    products = []
    selections = []
    for entry in plan:
        prod = known.get(entry["product_id"]) or get_product(
            entry["product_id"], db_path=db_path
        )
        if prod is None:
            continue
        original_id = prod["product_id"].removeprefix(f"session:{pet_id}:") if PUBLIC_SERVER else prod["product_id"]
        products.append(
            {
                "product_id": original_id,
                "name": prod["name"],
                "category": prod["category"],
                "serving_basis_g": prod["serving_basis_g"],
                "monthly_price_krw": prod.get("monthly_price_krw", 0),
                "label_complete": prod.get("label_complete", True),
                "nutrients": prod.get("nutrients", []),
            }
        )
        selections.append(
            {
                "product_id": original_id,
                "daily_amount_g": entry["daily_amount_g"],
                "active": entry["active"],
            }
        )
    return {
        "profile": {
            "name": pet["name"],
            "weight_kg": pet["weight_kg"],
            "breed": pet.get("breed") or "",
            "neutered": pet.get("neutered", False),
        },
        "products": products,
        "selections": selections,
    }


# ===========================================================================
# 단위 변환 (F-011) · 최저가 (F-021) · 대체 제품 추천 (F-019)
# ===========================================================================
class UnitConvertRequest(BaseModel):
    value: float
    unit: str
    nutrient: str = ""
    serving_basis_g: float = Field(gt=0, default=100.0)
    servings_per_pack: float = Field(gt=0, default=1.0)


@APP.get("/api/units")
def api_units():
    return {"supported": supported_units()}


@APP.post("/api/units/convert")
def api_units_convert(req: UnitConvertRequest):
    """라벨 표기(value unit)를 기준량당 mg 으로 환산한다. %, mg/kg, IU/kg, mg/정 지원."""
    try:
        mg = to_mg_per_serving(
            req.value,
            req.unit,
            req.nutrient,
            UnitContext(req.serving_basis_g, req.servings_per_pack),
        )
    except UnitError as exc:
        raise HTTPException(400, str(exc))
    return {"mg": mg, "value": req.value, "unit": req.unit, "nutrient": req.nutrient}


@APP.get("/api/prices/{product_id}")
def api_prices(product_id: str):
    """제품의 판매처별 가격과 최저가. 데모 제공자(prices.csv) 기반."""
    offer = best_offer(product_id)
    if offer is None:
        return {"product_id": product_id, "offers": [], "lowest_price_krw": None, "source": "demo"}
    return offer


class RecommendRequest(BaseModel):
    products: list[ProductIn]
    selections: list[SelectionIn]
    max_results: int = 5


@APP.post("/api/recommend")
def api_recommend(req: RecommendRequest):
    """현재 조합의 과잉·중복 신호를 줄이는 카탈로그 대체 제품을 순위로 제안한다. (F-019)"""
    products = _normalize_products([p.model_dump() for p in req.products])
    catalog = _catalog_products()
    if not products:
        raise HTTPException(400, "제품이 하나도 없습니다")
    try:
        return recommend_swaps(
            products,
            [s.model_dump() for s in req.selections],
            catalog,
            _standards_df(),
            max_results=req.max_results,
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc))


# ===========================================================================
# 소비자 앱 — 즐겨찾기 · 리뷰 · 주문(모의 결제)
# ===========================================================================
@APP.get("/api/favorites")
def api_favorites(
    db: str | None = Query(None), user: dict[str, Any] = Depends(current_user)
):
    from backend.shop import list_favorites

    return {"product_ids": list_favorites(user["user_id"], _db_path_from_query(db))}


@APP.put("/api/favorites/{product_id}")
def api_favorite_add(
    product_id: str,
    db: str | None = Query(None),
    user: dict[str, Any] = Depends(current_user),
):
    from backend.shop import add_favorite

    add_favorite(user["user_id"], product_id, _db_path_from_query(db))
    return {"ok": True}


@APP.delete("/api/favorites/{product_id}")
def api_favorite_remove(
    product_id: str,
    db: str | None = Query(None),
    user: dict[str, Any] = Depends(current_user),
):
    from backend.shop import remove_favorite

    remove_favorite(user["user_id"], product_id, _db_path_from_query(db))
    return {"ok": True}


@APP.get("/api/reviews/{product_id}")
def api_reviews(product_id: str, db: str | None = Query(None)):
    from backend.shop import product_reviews

    return product_reviews(product_id, _db_path_from_query(db))


@APP.get("/api/ratings")
def api_ratings(ids: str = Query(""), db: str | None = Query(None)):
    """쉼표로 구분된 product_id 들의 평균 평점·개수."""
    from backend.shop import ratings_for

    id_list = [x for x in ids.split(",") if x]
    return ratings_for(id_list, _db_path_from_query(db))


class ReviewIn(BaseModel):
    product_id: str
    rating: int = Field(ge=1, le=5)
    body: str = ""


@APP.post("/api/reviews", status_code=201)
def api_review_add(
    req: ReviewIn,
    db: str | None = Query(None),
    user: dict[str, Any] = Depends(current_user),
):
    from backend.shop import add_review

    rid = add_review(
        user["user_id"],
        user.get("display_name") or user["email"].split("@")[0],
        req.product_id,
        req.rating,
        req.body,
        _db_path_from_query(db),
    )
    return {"review_id": rid}


class OrderItemIn(BaseModel):
    product_id: str
    name: str
    qty: int = Field(ge=1)
    price_krw: int = Field(ge=0)


class OrderIn(BaseModel):
    items: list[OrderItemIn]
    address: str = ""


@APP.post("/api/orders", status_code=201)
def api_order_create(
    req: OrderIn,
    db: str | None = Query(None),
    user: dict[str, Any] = Depends(current_user),
):
    """모의 결제. 결제 승인 절차 없이 주문을 'paid' 로 기록한다."""
    from backend.shop import create_order

    if not req.items:
        raise HTTPException(400, "장바구니가 비어 있습니다.")
    total = sum(i.qty * i.price_krw for i in req.items)
    return create_order(
        user["user_id"],
        [i.model_dump() for i in req.items],
        total,
        req.address,
        _db_path_from_query(db),
    )


@APP.get("/api/orders")
def api_orders(
    db: str | None = Query(None), user: dict[str, Any] = Depends(current_user)
):
    from backend.shop import list_orders

    return list_orders(user["user_id"], _db_path_from_query(db))


def build_app() -> FastAPI:
    return APP


# 빌드된 React 앱을 같은 서버에서 서빙한다(설치형 셸이 http://127.0.0.1:PORT/ 로드).
if FRONTEND_DIST.is_dir():
    APP.mount(
        "/assets",
        StaticFiles(directory=str(FRONTEND_DIST / "assets")),
        name="assets",
    )

    @APP.get("/")
    def _spa_root():
        return FileResponse(str(FRONTEND_DIST / "index.html"))

    @APP.get("/{path:path}")
    def _spa_fallback(path: str):
        if path == "api" or path.startswith("api/"):
            raise HTTPException(404, "API를 찾을 수 없습니다.")
        target = (FRONTEND_DIST / path).resolve()
        if not target.is_relative_to(FRONTEND_DIST.resolve()):
            raise HTTPException(404, "파일을 찾을 수 없습니다.")
        if target.is_file():
            return FileResponse(str(target))
        return FileResponse(str(FRONTEND_DIST / "index.html"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--db", default=str(DEFAULT_DB))
    args = parser.parse_args()
    uvicorn.run(
        "backend.api:build_app",
        host=args.host,
        port=args.port,
        reload=False,
        factory=True,
    )


if __name__ == "__main__":
    main()
