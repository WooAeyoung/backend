"""소비자 앱 데이터 계층 — 즐겨찾기 · 리뷰 · 주문 (사용자별).

모의 결제: 주문은 status='paid' 로 바로 기록된다. 실 결제(토스/Stripe)는
create_order 앞단에 결제 승인 단계만 끼우면 된다.
"""

from __future__ import annotations

import json
import secrets
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import sqlalchemy as sa

from backend.database import _session


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


# --------------------------------------------------------------------------- #
# 즐겨찾기
# --------------------------------------------------------------------------- #
def list_favorites(user_id: int, db_path: Path | None = None) -> list[str]:
    with _session(db_path) as conn:
        rows = conn.execute(
            sa.text("SELECT product_id FROM favorites WHERE user_id=:u ORDER BY created_at DESC"),
            {"u": user_id},
        ).fetchall()
        return [r.product_id for r in rows]


def add_favorite(user_id: int, product_id: str, db_path: Path | None = None) -> None:
    with _session(db_path) as conn:
        conn.execute(
            sa.text(
                "INSERT INTO favorites(user_id, product_id, created_at) VALUES (:u,:p,:c) "
                "ON CONFLICT(user_id, product_id) DO NOTHING"
            ),
            {"u": user_id, "p": product_id, "c": _now()},
        )
        conn.commit()


def remove_favorite(user_id: int, product_id: str, db_path: Path | None = None) -> None:
    with _session(db_path) as conn:
        conn.execute(
            sa.text("DELETE FROM favorites WHERE user_id=:u AND product_id=:p"),
            {"u": user_id, "p": product_id},
        )
        conn.commit()


# --------------------------------------------------------------------------- #
# 리뷰
# --------------------------------------------------------------------------- #
def product_reviews(product_id: str, db_path: Path | None = None) -> dict[str, Any]:
    with _session(db_path) as conn:
        rows = conn.execute(
            sa.text(
                "SELECT review_id, author, rating, body, created_at FROM reviews "
                "WHERE product_id=:p ORDER BY created_at DESC LIMIT 100"
            ),
            {"p": product_id},
        ).fetchall()
    reviews = [
        {
            "review_id": r.review_id,
            "author": r.author or "익명",
            "rating": r.rating,
            "body": r.body or "",
            "created_at": r.created_at,
        }
        for r in rows
    ]
    dist = {i: 0 for i in range(1, 6)}
    for r in reviews:
        dist[r["rating"]] += 1
    count = len(reviews)
    avg = round(sum(r["rating"] for r in reviews) / count, 2) if count else 0.0
    return {"summary": {"avg": avg, "count": count, "dist": dist}, "reviews": reviews}


def add_review(
    user_id: int,
    author: str,
    product_id: str,
    rating: int,
    body: str,
    db_path: Path | None = None,
) -> int:
    rating = max(1, min(5, int(rating)))
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text(
                "INSERT INTO reviews(user_id, product_id, author, rating, body, created_at) "
                "VALUES (:u,:p,:a,:r,:b,:c) RETURNING review_id"
            ),
            {"u": user_id, "p": product_id, "a": author, "r": rating, "b": body, "c": _now()},
        )
        review_id = int(r.scalar_one())
        conn.commit()
        return review_id


def ratings_for(product_ids: list[str], db_path: Path | None = None) -> dict[str, dict]:
    if not product_ids:
        return {}
    with _session(db_path) as conn:
        rows = conn.execute(
            sa.text(
                "SELECT product_id, AVG(rating) avg, COUNT(*) n FROM reviews "
                "WHERE product_id IN :ids GROUP BY product_id"
            ).bindparams(sa.bindparam("ids", expanding=True)),
            {"ids": product_ids},
        ).fetchall()
    return {r.product_id: {"avg": round(float(r.avg), 2), "count": int(r.n)} for r in rows}


# --------------------------------------------------------------------------- #
# 주문 (모의 결제)
# --------------------------------------------------------------------------- #
def create_order(
    user_id: int,
    items: list[dict[str, Any]],
    total_krw: int,
    address: str,
    db_path: Path | None = None,
) -> dict[str, Any]:
    with _session(db_path) as conn:
        r = conn.execute(
            sa.text(
                "INSERT INTO orders(user_id, items_json, total_krw, address, status, created_at) "
                "VALUES (:u,:i,:t,:a,'paid',:c) RETURNING order_id"
            ),
            {
                "u": user_id,
                "i": json.dumps(items, ensure_ascii=False),
                "t": int(total_krw),
                "a": address,
                "c": _now(),
            },
        )
        oid = int(r.scalar_one())
        conn.commit()
    return {"order_id": oid, "status": "paid", "total_krw": int(total_krw)}


def list_orders(user_id: int, db_path: Path | None = None) -> list[dict[str, Any]]:
    with _session(db_path) as conn:
        rows = conn.execute(
            sa.text(
                "SELECT order_id, items_json, total_krw, address, status, created_at "
                "FROM orders WHERE user_id=:u ORDER BY created_at DESC LIMIT 100"
            ),
            {"u": user_id},
        ).fetchall()
    out = []
    for r in rows:
        try:
            items = json.loads(r.items_json)
        except json.JSONDecodeError:
            items = []
        out.append(
            {
                "order_id": r.order_id,
                "items": items,
                "total_krw": r.total_krw,
                "address": r.address,
                "status": r.status,
                "created_at": r.created_at,
            }
        )
    return out


# --------------------------------------------------------------------------- #
# 데모 리뷰 시드
# --------------------------------------------------------------------------- #
_SEED = [
    ("food_a", 5, "우리 강아지가 정말 잘 먹어요. 변 상태도 좋아졌습니다.", "몽이맘"),
    ("food_a", 4, "가성비 좋고 알갱이 크기가 적당해요.", "초코아빠"),
    ("food_a", 4, "무난하게 급여 중입니다. 재구매 의사 있어요.", "봄이누나"),
    ("supp_cal", 5, "관절 걱정돼서 먹이는데 잘 먹네요.", "루이보스"),
    ("supp_cal", 3, "효과는 아직 잘 모르겠지만 거부감 없이 먹어요.", "익명"),
    ("snack_a", 4, "훈련 간식으로 딱이에요. 작게 잘라 급여합니다.", "댕댕이"),
    ("multi_a", 5, "종합 영양제로 챙겨주고 있어요. 만족합니다.", "하늘이"),
    ("food_lamb", 4, "알러지 있는 아이인데 잘 맞아요.", "포poh"),
    ("food_grainfree", 5, "털에 윤기가 도는 느낌. 계속 급여할게요.", "연어러버"),
]


_SEED_USER_EMAIL = "seed@wooaeyoung.internal"


def seed_reviews_if_empty(db_path: Path | None = None) -> None:
    with _session(db_path) as conn:
        n = conn.execute(sa.text("SELECT COUNT(*) c FROM reviews")).scalar()
        if n and n > 0:
            return
        # 데모 리뷰의 작성자는 실제 계정이 없다. user_id=0을 그대로 박아 넣으면 FK를
        # 엄격히 검사하는 Postgres에서 깨지므로(로그인 불가능한) 시드 전용 사용자를 만들어 참조한다.
        seed_user_id = conn.execute(
            sa.text("SELECT user_id FROM users WHERE email=:e"), {"e": _SEED_USER_EMAIL}
        ).scalar()
        if seed_user_id is None:
            seed_user_id = conn.execute(
                sa.text(
                    "INSERT INTO users(email, password_hash, password_salt, display_name, created_at) "
                    "VALUES (:e, :h, :s, '데모', :c) RETURNING user_id"
                ),
                {
                    "e": _SEED_USER_EMAIL,
                    "h": secrets.token_hex(32),  # 로그인 불가한 무작위 값(실제 비밀번호 아님)
                    "s": secrets.token_hex(16),
                    "c": _now(),
                },
            ).scalar_one()
        for pid, rating, body, author in _SEED:
            conn.execute(
                sa.text(
                    "INSERT INTO reviews(user_id, product_id, author, rating, body, created_at) "
                    "VALUES (:u,:p,:a,:r,:b,:c)"
                ),
                {"u": seed_user_id, "p": pid, "a": author, "r": rating, "b": body, "c": _now()},
            )
        conn.commit()
