"""반려동물 프로필·식단을 SQLite에 저장/복원하고, 분석 API를 제공한다.

구현 기능
- F-025: SQLite 스키마 연결 (sql/schema.sql 기반)
- F-026: 프로필·식단 CRUD (생성/읽기/수정/삭제)
- F-027: 분석 API (FastAPI) — 영양소 합산·상태 판정·기여도 반환

이 모듈은 Streamlit 앱에서 로컬 SQLite 파일(db/wooaeyoung.db)을 통해
사용되며, 동시에 FastAPI 서버에서도 동일한 엔진을 재사용한다.
"""

from __future__ import annotations

import os
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import pandas as pd
import sqlalchemy as sa

from backend.nutrition import (
    FeedingSelection,
    calculate_intake,
    product_contributions,
    summarize_intake,
    validate_products,
)

# 설치형 셸은 쓰기 가능한 사용자 디렉터리를 WOOAEYOUNG_DB 로 넘긴다(패키징 시 필수).
DB_PATH = Path(
    os.environ.get("WOOAEYOUNG_DB")
    or (Path(__file__).resolve().parent / "db" / "wooaeyoung.db")
)


def _use_postgres() -> bool:
    """Vercel 등 웹 배포에서는 DATABASE_URL로 Postgres를 쓴다.
    api.py는 db_path를 항상(기본값 DEFAULT_DB로) 구체적인 Path로 넘기므로
    "db_path is None"으로는 구분할 수 없다 — DATABASE_URL 유무만으로 분기한다.
    데스크톱 앱과 테스트는 DATABASE_URL을 설정하지 않으므로 항상 SQLite를 그대로 쓴다."""
    return bool(os.environ.get("DATABASE_URL"))


def _postgres_url() -> str:
    url = os.environ["DATABASE_URL"]
    # Neon/Vercel Postgres가 주는 postgres:// 또는 postgresql:// 를 psycopg 드라이버로 명시한다.
    if url.startswith("postgres://"):
        url = "postgresql+psycopg://" + url[len("postgres://"):]
    elif url.startswith("postgresql://") and "+psycopg" not in url:
        url = "postgresql+psycopg://" + url[len("postgresql://"):]
    return url


def _engine(db_path: Path | None = None) -> sa.engine.Engine:
    if _use_postgres():
        return sa.create_engine(_postgres_url())
    path = (db_path or DB_PATH).expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    url = sa.engine.URL.create("sqlite", database=str(path))
    eng = sa.create_engine(url, connect_args={"check_same_thread": False})
    return eng


_schema_ready: set[str] = set()


def _ensure_schema(db_path: Path | None = None) -> Path:
    """해당 경로의 스키마를 처음 한 번 보장한다(CREATE TABLE IF NOT EXISTS)."""
    path = (db_path or DB_PATH).expanduser().resolve()
    key = str(path)
    if key not in _schema_ready:
        init_db(path)
        _schema_ready.add(key)
    return path


def init_db(db_path: Path | None = None, schema_sql: str | None = None) -> None:
    """데이터베이스 파일과 테이블을 생성하고 가벼운 컬럼 마이그레이션을 적용한다."""
    postgres = _use_postgres()
    eng = _engine(db_path)
    schema = schema_sql or (_default_schema_sql_postgres() if postgres else _default_schema_sql())
    with eng.connect() as conn:
        if not postgres:
            conn.execute(sa.text("PRAGMA foreign_keys = ON"))
        for stmt in schema.split(";"):
            stmt = stmt.strip()
            if stmt:
                conn.execute(sa.text(stmt))
        _migrate(conn, postgres)
        conn.commit()


def _migrate(conn: sa.Connection, postgres: bool = False) -> None:
    """기존 DB 에 새로 추가된 컬럼을 채운다(ADD COLUMN IF NOT EXISTS 대용)."""
    additions = {
        "pets": [("user_id", "INTEGER")],
        "products": [
            ("brand", "TEXT"),
            ("source_url", "TEXT"),
        ],
        "nutrient_standards": [
            ("source_url", "TEXT"),
            ("version", "TEXT"),
            ("basis", "TEXT"),
        ],
    }
    for table, cols in additions.items():
        try:
            if postgres:
                existing = {
                    row[0]
                    for row in conn.execute(
                        sa.text(
                            "SELECT column_name FROM information_schema.columns "
                            "WHERE table_name=:t"
                        ),
                        {"t": table},
                    )
                }
            else:
                existing = {
                    row[1]
                    for row in conn.execute(sa.text(f"PRAGMA table_info({table})"))
                }
        except Exception:
            continue
        if not existing:
            continue
        for name, decl in cols:
            if name not in existing:
                conn.execute(sa.text(f"ALTER TABLE {table} ADD COLUMN {name} {decl}"))


def _default_schema_sql() -> str:
    return """
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users (
    user_id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    display_name TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pets (
    pet_id INTEGER PRIMARY KEY,
    user_id INTEGER REFERENCES users(user_id),
    name TEXT NOT NULL,
    species TEXT NOT NULL CHECK (species IN ('dog','cat')),
    birth_date TEXT,
    weight_kg REAL NOT NULL CHECK (weight_kg > 0),
    life_stage TEXT NOT NULL,
    breed TEXT,
    neutered INTEGER NOT NULL DEFAULT 0 CHECK (neutered IN (0,1))
);
CREATE TABLE IF NOT EXISTS products (
    product_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('주식','간식','영양제')),
    serving_basis_g REAL NOT NULL CHECK (serving_basis_g > 0),
    monthly_price_krw INTEGER CHECK (monthly_price_krw >= 0),
    source TEXT NOT NULL,
    label_complete INTEGER NOT NULL DEFAULT 1 CHECK (label_complete IN (0,1))
);
CREATE TABLE IF NOT EXISTS product_nutrients (
    product_id TEXT NOT NULL REFERENCES products(product_id),
    nutrient TEXT NOT NULL,
    amount_mg REAL NOT NULL CHECK (amount_mg >= 0),
    label_complete INTEGER NOT NULL CHECK (label_complete IN (0,1)),
    PRIMARY KEY (product_id, nutrient)
);
CREATE TABLE IF NOT EXISTS feeding_plans (
    pet_id INTEGER NOT NULL REFERENCES pets(pet_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    daily_amount_g REAL NOT NULL CHECK (daily_amount_g >= 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    PRIMARY KEY (pet_id, product_id)
);
CREATE TABLE IF NOT EXISTS nutrient_standards (
    species TEXT NOT NULL,
    life_stage TEXT NOT NULL,
    nutrient TEXT NOT NULL,
    demo_min_mg REAL NOT NULL CHECK (demo_min_mg >= 0),
    demo_max_mg REAL NOT NULL CHECK (demo_max_mg >= demo_min_mg),
    source TEXT NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0 CHECK (verified IN (0,1)),
    PRIMARY KEY (species, life_stage, nutrient)
);
CREATE TABLE IF NOT EXISTS favorites (
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    product_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (user_id, product_id)
);
CREATE TABLE IF NOT EXISTS reviews (
    review_id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    product_id TEXT NOT NULL,
    author TEXT,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
    order_id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    items_json TEXT NOT NULL,
    total_krw INTEGER NOT NULL,
    address TEXT,
    status TEXT NOT NULL DEFAULT 'paid',
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_product_nutrients_nutrient ON product_nutrients(nutrient);
CREATE INDEX IF NOT EXISTS idx_feeding_plans_pet_active ON feeding_plans(pet_id, active);
CREATE INDEX IF NOT EXISTS idx_reviews_product ON reviews(product_id);
CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id);
"""


def _default_schema_sql_postgres() -> str:
    """Vercel Postgres(Neon) 배포용. SQLite 버전과 테이블/제약은 동일하고,
    자동증가 기본키만 GENERATED ALWAYS AS IDENTITY로 바꾼다(PRAGMA는 불필요)."""
    return """
CREATE TABLE IF NOT EXISTS users (
    user_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    display_name TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pets (
    pet_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id INTEGER REFERENCES users(user_id),
    name TEXT NOT NULL,
    species TEXT NOT NULL CHECK (species IN ('dog','cat')),
    birth_date TEXT,
    weight_kg REAL NOT NULL CHECK (weight_kg > 0),
    life_stage TEXT NOT NULL,
    breed TEXT,
    neutered INTEGER NOT NULL DEFAULT 0 CHECK (neutered IN (0,1))
);
CREATE TABLE IF NOT EXISTS products (
    product_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('주식','간식','영양제')),
    serving_basis_g REAL NOT NULL CHECK (serving_basis_g > 0),
    monthly_price_krw INTEGER CHECK (monthly_price_krw >= 0),
    source TEXT NOT NULL,
    label_complete INTEGER NOT NULL DEFAULT 1 CHECK (label_complete IN (0,1))
);
CREATE TABLE IF NOT EXISTS product_nutrients (
    product_id TEXT NOT NULL REFERENCES products(product_id),
    nutrient TEXT NOT NULL,
    amount_mg REAL NOT NULL CHECK (amount_mg >= 0),
    label_complete INTEGER NOT NULL CHECK (label_complete IN (0,1)),
    PRIMARY KEY (product_id, nutrient)
);
CREATE TABLE IF NOT EXISTS feeding_plans (
    pet_id INTEGER NOT NULL REFERENCES pets(pet_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    daily_amount_g REAL NOT NULL CHECK (daily_amount_g >= 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    PRIMARY KEY (pet_id, product_id)
);
CREATE TABLE IF NOT EXISTS nutrient_standards (
    species TEXT NOT NULL,
    life_stage TEXT NOT NULL,
    nutrient TEXT NOT NULL,
    demo_min_mg REAL NOT NULL CHECK (demo_min_mg >= 0),
    demo_max_mg REAL NOT NULL CHECK (demo_max_mg >= demo_min_mg),
    source TEXT NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0 CHECK (verified IN (0,1)),
    PRIMARY KEY (species, life_stage, nutrient)
);
CREATE TABLE IF NOT EXISTS favorites (
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    product_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (user_id, product_id)
);
CREATE TABLE IF NOT EXISTS reviews (
    review_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    product_id TEXT NOT NULL,
    author TEXT,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
    order_id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    items_json TEXT NOT NULL,
    total_krw INTEGER NOT NULL,
    address TEXT,
    status TEXT NOT NULL DEFAULT 'paid',
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_product_nutrients_nutrient ON product_nutrients(nutrient);
CREATE INDEX IF NOT EXISTS idx_feeding_plans_pet_active ON feeding_plans(pet_id, active);
CREATE INDEX IF NOT EXISTS idx_reviews_product ON reviews(product_id);
CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id);
"""


# ---------------------------------------------------------------------------
# Pet (프로필) CRUD
# ---------------------------------------------------------------------------
@contextmanager
def _session(db_path: Path | None = None):
    _ensure_schema(db_path)
    eng = _engine(db_path)
    with eng.begin() as conn:
        yield conn


def create_pet(
    name: str,
    species: str,
    weight_kg: float,
    life_stage: str,
    *,
    birth_date: str | None = None,
    breed: str | None = None,
    neutered: bool = False,
    user_id: int | None = None,
    db_path: Path | None = None,
) -> int:
    """펫을 생성하고 pet_id를 반환한다."""
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text(
                "INSERT INTO pets(user_id, name, species, birth_date, weight_kg, life_stage, breed, neutered) "
                "VALUES (:user_id, :name, :species, :birth_date, :weight_kg, :life_stage, :breed, :neutered) "
                "RETURNING pet_id"
            ),
            {
                "user_id": user_id,
                "name": name,
                "species": species,
                "birth_date": birth_date,
                "weight_kg": weight_kg,
                "life_stage": life_stage,
                "breed": breed,
                "neutered": 1 if neutered else 0,
            },
        )
        pet_id = int(r.scalar_one())
        conn.commit()
        return pet_id


def get_pet(
    pet_id: int, db_path: Path | None = None, *, user_id: int | None = None
) -> dict[str, Any] | None:
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text("SELECT pet_id, user_id, name, species, birth_date, weight_kg, life_stage, breed, neutered "
                    "FROM pets WHERE pet_id=:pet_id"),
            {"pet_id": pet_id},
        )
        row = r.fetchone()
        if row is None:
            return None
        if user_id is not None and row.user_id is not None and row.user_id != user_id:
            return None  # 다른 사용자의 펫은 감춘다
        return {
            "pet_id": row.pet_id,
            "user_id": row.user_id,
            "name": row.name,
            "species": row.species,
            "birth_date": row.birth_date,
            "weight_kg": float(row.weight_kg),
            "life_stage": row.life_stage,
            "breed": row.breed,
            "neutered": bool(row.neutered),
        }


def update_pet(
    pet_id: int,
    *,
    name: str | None = None,
    weight_kg: float | None = None,
    life_stage: str | None = None,
    breed: str | None = None,
    neutered: bool | None = None,
    db_path: Path | None = None,
) -> bool:
    with _session(db_path) as conn:
        sets: list[str] = []
        params: dict[str, Any] = {"pet_id": pet_id}
        if name is not None:
            sets.append("name=:name"); params["name"] = name
        if weight_kg is not None:
            sets.append("weight_kg=:weight_kg"); params["weight_kg"] = weight_kg
        if life_stage is not None:
            sets.append("life_stage=:life_stage"); params["life_stage"] = life_stage
        if breed is not None:
            sets.append("breed=:breed"); params["breed"] = breed
        if neutered is not None:
            sets.append("neutered=:neutered"); params["neutered"] = 1 if neutered else 0
        if not sets:
            return False
        params["pet_id"] = pet_id
        r = conn.execute(
            sa.text(f"UPDATE pets SET {', '.join(sets)} WHERE pet_id=:pet_id"),
            params,
        )
        conn.commit()
        return r.rowcount > 0


def delete_pet(pet_id: int, db_path: Path | None = None) -> bool:
    with _session(db_path) as conn:
        r = conn.execute(sa.text("DELETE FROM feeding_plans WHERE pet_id=:pet_id"),
                         {"pet_id": pet_id})
        conn.execute(sa.text("DELETE FROM pets WHERE pet_id=:pet_id"),
                    {"pet_id": pet_id})
        conn.commit()
        return r.rowcount >= 0


def list_pets(
    db_path: Path | None = None, *, user_id: int | None = None
) -> list[dict[str, Any]]:
    with _session(db_path) as conn:
        if user_id is None:
            rows = conn.execute(
                sa.text(
                    "SELECT pet_id, user_id, name, species, birth_date, weight_kg, life_stage, breed, neutered "
                    "FROM pets ORDER BY pet_id"
                )
            ).fetchall()
        else:
            rows = conn.execute(
                sa.text(
                    "SELECT pet_id, user_id, name, species, birth_date, weight_kg, life_stage, breed, neutered "
                    "FROM pets WHERE user_id=:uid OR user_id IS NULL ORDER BY pet_id"
                ),
                {"uid": user_id},
            ).fetchall()
        return [
            {
                "pet_id": row.pet_id,
                "user_id": row.user_id,
                "name": row.name,
                "species": row.species,
                "birth_date": row.birth_date,
                "weight_kg": float(row.weight_kg),
                "life_stage": row.life_stage,
                "breed": row.breed,
                "neutered": bool(row.neutered),
            }
            for row in rows
        ]


# ---------------------------------------------------------------------------
# Product / nutrient CRUD
# ---------------------------------------------------------------------------
def upsert_product(
    product_id: str,
    name: str,
    category: str,
    serving_basis_g: float,
    monthly_price_krw: int | None = None,
    source: str | None = None,
    label_complete: bool = True,
    nutrients: list[tuple[str, float, bool]] | None = None,
    db_path: Path | None = None,
) -> None:
    """제품을 저장하거나 갱신한다. 영양소는 함께 교체된다."""
    if source is None:
        source = "manual"
    with _session(db_path) as conn:
        conn.execute(
            sa.text(
                "INSERT INTO products(product_id, name, category, serving_basis_g, monthly_price_krw, source, label_complete) "
                "VALUES (:product_id, :name, :category, :serving_basis_g, :monthly_price_krw, :source, :label_complete) "
                "ON CONFLICT(product_id) DO UPDATE SET name=:name, category=:category, serving_basis_g=:serving_basis_g, "
                "monthly_price_krw=:monthly_price_krw, source=:source, label_complete=:label_complete"
            ),
            {
                "product_id": product_id,
                "name": name,
                "category": category,
                "serving_basis_g": serving_basis_g,
                "monthly_price_krw": monthly_price_krw or 0,
                "source": source,
                "label_complete": 1 if label_complete else 0,
            },
        )
        conn.execute(sa.text("DELETE FROM product_nutrients WHERE product_id=:product_id"),
                    {"product_id": product_id})
        if nutrients:
            rows = [
                {
                    "product_id": product_id,
                    "nutrient": n,
                    "amount_mg": a,
                    "label_complete": 1 if lc else 0,
                }
                for n, a, lc in nutrients
            ]
            conn.execute(
                sa.text(
                    "INSERT INTO product_nutrients(product_id, nutrient, amount_mg, label_complete) "
                    "VALUES (:product_id, :nutrient, :amount_mg, :label_complete)"
                ),
                rows,
            )
        conn.commit()


def get_product(product_id: str, db_path: Path | None = None) -> dict[str, Any] | None:
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text("SELECT product_id, name, category, serving_basis_g, monthly_price_krw, source, label_complete "
                    "FROM products WHERE product_id=:product_id"),
            {"product_id": product_id},
        )
        row = r.fetchone()
        if row is None:
            return None
        nutrients_r = conn.execute(
            sa.text("SELECT nutrient, amount_mg, label_complete FROM product_nutrients WHERE product_id=:product_id "
                    "ORDER BY nutrient"),
            {"product_id": product_id},
        )
        nutrients = [
            {"nutrient": n.nutrient, "amount_mg": float(n.amount_mg), "label_complete": bool(n.label_complete)}
            for n in nutrients_r.fetchall()
        ]
        return {
            "product_id": row.product_id,
            "name": row.name,
            "category": row.category,
            "serving_basis_g": float(row.serving_basis_g),
            "monthly_price_krw": int(row.monthly_price_krw),
            "source": row.source,
            "label_complete": bool(row.label_complete),
            "nutrients": nutrients,
        }


def delete_product(product_id: str, db_path: Path | None = None) -> bool:
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text("DELETE FROM feeding_plans WHERE product_id=:product_id"),
            {"product_id": product_id},
        )
        conn.execute(sa.text("DELETE FROM product_nutrients WHERE product_id=:product_id"),
                    {"product_id": product_id})
        r2 = conn.execute(sa.text("DELETE FROM products WHERE product_id=:product_id"),
                         {"product_id": product_id})
        conn.commit()
        return r2.rowcount > 0


def list_products(db_path: Path | None = None) -> list[dict[str, Any]]:
    with _session(db_path) as conn:
        rows = conn.execute(sa.text(
            "SELECT product_id, name, category, serving_basis_g, monthly_price_krw, source, label_complete "
            "FROM products ORDER BY category, name"
        )).fetchall()
        out: list[dict[str, Any]] = []
        for row in rows:
            nutrients_r = conn.execute(
                sa.text("SELECT nutrient, amount_mg, label_complete FROM product_nutrients WHERE product_id=:product_id "
                        "ORDER BY nutrient"),
                {"product_id": row.product_id},
            )
            nutrients = [
                {"nutrient": n.nutrient, "amount_mg": float(n.amount_mg), "label_complete": bool(n.label_complete)}
                for n in nutrients_r.fetchall()
            ]
            out.append({
                "product_id": row.product_id,
                "name": row.name,
                "category": row.category,
                "serving_basis_g": float(row.serving_basis_g),
                "monthly_price_krw": int(row.monthly_price_krw),
                "source": row.source,
                "label_complete": bool(row.label_complete),
                "nutrients": nutrients,
            })
        return out


# ---------------------------------------------------------------------------
# Feeding plan (식단) CRUD
# ---------------------------------------------------------------------------
def set_feeding_plan(
    pet_id: int,
    product_id: str,
    daily_amount_g: float,
    active: bool = True,
    db_path: Path | None = None,
) -> None:
    with _session(db_path) as conn:
        conn.execute(
            sa.text(
                "INSERT INTO feeding_plans(pet_id, product_id, daily_amount_g, active) "
                "VALUES (:pet_id, :product_id, :daily_amount_g, :active) "
                "ON CONFLICT(pet_id, product_id) DO UPDATE SET daily_amount_g=:daily_amount_g, active=:active"
            ),
            {
                "pet_id": pet_id,
                "product_id": product_id,
                "daily_amount_g": daily_amount_g,
                "active": 1 if active else 0,
            },
        )
        conn.commit()


def remove_feeding_plan(pet_id: int, product_id: str, db_path: Path | None = None) -> bool:
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text("DELETE FROM feeding_plans WHERE pet_id=:pet_id AND product_id=:product_id"),
            {"pet_id": pet_id, "product_id": product_id},
        )
        conn.commit()
        return r.rowcount > 0


def get_feeding_plan(pet_id: int, db_path: Path | None = None) -> list[dict[str, Any]]:
    with _session(db_path) as conn:
        rows = conn.execute(
            sa.text("SELECT product_id, daily_amount_g, active FROM feeding_plans "
                    "WHERE pet_id=:pet_id ORDER BY product_id"),
            {"pet_id": pet_id},
        ).fetchall()
        return [
            {"product_id": row.product_id, "daily_amount_g": float(row.daily_amount_g), "active": bool(row.active)}
            for row in rows
        ]


# ---------------------------------------------------------------------------
# 분석 API용 엔진
# ---------------------------------------------------------------------------
def products_to_dataframe(products: list[dict[str, Any]]) -> pd.DataFrame:
    """제품 목록(내부 nutrients 포함)을 앱 계산용 DataFrame 으로 변환한다."""
    rows: list[dict[str, Any]] = []
    for p in products:
        for n in p.get("nutrients", []):
            rows.append({
                "product_id": p["product_id"],
                "product_name": p["name"],
                "category": p["category"],
                "serving_basis_g": float(p["serving_basis_g"]),
                "nutrient": n["nutrient"],
                "amount_mg": float(n["amount_mg"]),
                "label_complete": bool(n.get("label_complete", p.get("label_complete", True))),
                "monthly_price_krw": int(p.get("monthly_price_krw", 0)),
            })
    return pd.DataFrame(rows)


def standards_to_dataframe(rows: list[dict[str, Any]]) -> pd.DataFrame:
    return pd.DataFrame(rows)


def analyze(
    products: list[dict[str, Any]],
    selections: list[FeedingSelection],
    standards: pd.DataFrame,
) -> dict[str, Any]:
    """백엔드 분석 결과(JSON serializable)를 반환한다."""
    products_df = products_to_dataframe(products)
    validate_products(products_df)
    intake = calculate_intake(products_df, selections)
    summary = summarize_intake(intake, standards)
    return {
        "summary": summary.to_dict(orient="records"),
        "intake": intake[["nutrient", "product_name", "daily_nutrient_mg", "label_complete"]].to_dict(orient="records"),
    }


def product_contribution_list(
    products: list[dict[str, Any]],
    selections: list[FeedingSelection],
    nutrient: str,
) -> list[dict[str, Any]]:
    products_df = products_to_dataframe(products)
    validate_products(products_df)
    intake = calculate_intake(products_df, selections)
    contribution = product_contributions(intake, nutrient)
    return contribution.to_dict(orient="records")
