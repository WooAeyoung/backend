from __future__ import annotations

from pathlib import Path
from dataclasses import dataclass
from typing import Any

import pandas as pd
import streamlit as st

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
from backend.database import (
    DB_PATH,
    create_pet,
    delete_product,
    delete_pet,
    get_feeding_plan,
    get_pet,
    list_products,
    list_pets,
    set_feeding_plan,
    upsert_product,
)

ROOT = Path(__file__).resolve().parent.parent
PRODUCTS_PATH = ROOT / "data" / "processed" / "products.csv"
STANDARDS_PATH = ROOT / "data" / "processed" / "nutrient_standards.csv"

st.set_page_config(page_title="우애영", page_icon="🐾", layout="wide")

st.markdown(
    """
    <style>
    .block-container {max-width: 1180px; padding-top: 2rem;}
    [data-testid="stMetric"] {background:#f7f7fb; border:1px solid #ececf3;
      border-radius:16px; padding:16px;}
    .hero {padding:24px; border-radius:22px;
      background:linear-gradient(120deg,#4d7cfe,#7d5cff); color:white;
      margin-bottom:20px;}
    .hero h1 {margin:0 0 8px 0;}
    .report-card {border:1px solid #d0d5dd; border-radius:12px; padding:16px;
      background:#fafbfc;}
    .report-card h3 {margin-top:0; color:#1f2937;}
    .comparison-grid {display:flex; gap:12px; flex-wrap:wrap;}
    .comparison-card {flex:1; min-width:280px; border:1px solid #d0d5dd;
      border-radius:12px; padding:14px; background:#f8f9fb;}
    .comparison-card h4 {margin:0 0 8px 0; font-size:0.95rem;}
    .comparison-card.before {border-left:4px solid #7d5cff;}
    .comparison-card.after {border-left:4px solid #10b981;}
    @media print {
      .block-container {max-width:100%; padding:0;}
      .hero, .stButton, .stSidebar, [data-testid="stSidebar"] {display:none !important;}
      .report-card {border:1px solid #999; break-inside:avoid;}
      .comparison-grid {flex-direction:row !important;}
    }
    </style>
    """,
    unsafe_allow_html=True,
)

st.markdown(
    '<div class="hero"><h1>🐾 우애영</h1>'
    '<p>여러 제품을 하나의 식단으로 계산하는 영양 의사결정 보조 서비스</p></div>',
    unsafe_allow_html=True,
)
st.warning(
    "현재 수치는 기능 검증용 데모 기준입니다. 질병 진단·치료·처방 또는 실제 급여 판단에 사용할 수 없습니다.",
    icon="⚠️",
)

base_products = pd.read_csv(PRODUCTS_PATH)
standards = pd.read_csv(STANDARDS_PATH)

st.session_state.setdefault("extra_product_rows", [])  # list[pd.DataFrame]
st.session_state.setdefault("label_draft", None)
st.session_state.setdefault("search_query", "")
st.session_state.setdefault("baseline_selections", {})  # product_id -> FeedingSelection


def _to_float(value: float) -> float:
    return 0.0 if pd.isna(value) else float(value)


status_icons = {
    "정보 충분": "✅",
    "중복 가능": "🟠",
    "기준 초과 가능": "🔴",
    "참고 범위 미만": "🔵",
    "정보 부족": "⚪",
}


def status_icon(status: str) -> str:
    return status_icons.get(status, "⚪")


def _build_selections(
    catalog: pd.DataFrame,
    baseline: dict[str, FeedingSelection] | None = None,
) -> list[FeedingSelection]:
    """현재 화면의 제품 활성/급여량 상태를 FeedingSelection 목록으로 만든다."""
    selections: list[FeedingSelection] = []
    defaults = {"food_a": 120.0, "supp_cal": 2.0, "snack_a": 10.0, "multi_a": 3.0}
    for row in catalog.itertuples():
        active = st.session_state.get(f"active_{row.product_id}", True)
        amount = st.session_state.get(
            f"amount_{row.product_id}",
            defaults.get(row.product_id, 1.0),
        )
        selections.append(FeedingSelection(row.product_id, float(amount), active))
    return selections


def _compute_summary(
    products: pd.DataFrame,
    selections: list[FeedingSelection],
) -> pd.DataFrame:
    intake = calculate_intake(products, selections)
    return summarize_intake(intake, standards)


# ---------------------------------------------------------------------------
# 라벨 업로드 패널 (기존 흐름 유지 + 검색과 분리)
# ---------------------------------------------------------------------------
with st.expander("📷 라벨 업로드로 제품 추가 (OCR 초안)", expanded=False):
    st.caption(
        "사진 또는 텍스트에서 후보값을 뽑아 편집 표로 보여줍니다. 값은 초안이며, "
        "사람이 확인 버튼을 눌러야 조합에 반영됩니다. OCR 결과를 자동 확정하지 않습니다."
    )
    upload_col, text_col = st.columns(2)
    with upload_col:
        image_file = st.file_uploader(
            "영양성분 라벨 사진", type=["png", "jpg", "jpeg", "webp"]
        )
    with text_col:
        pasted_text = st.text_area(
            "또는 라벨 텍스트 붙여넣기",
            height=150,
            placeholder=(
                "예)\n튼튼 칼슘 영양제\n1일 2정(2g) 기준\n"
                "칼슘 420mg\n인 160mg\n비타민D 8µg"
            ),
        )

    if st.button("초안 추출", type="primary"):
        source_text = pasted_text.strip()
        if not source_text and image_file is not None:
            try:
                source_text = extract_text_from_image(image_file.getvalue())
                st.session_state["ocr_raw_text"] = source_text
            except RuntimeError as exc:
                st.warning(str(exc))
                source_text = ""
        if source_text:
            st.session_state.label_draft = parse_label_text(source_text)
        else:
            st.info("이미지 자동 추출이 어려우면 라벨 텍스트를 붙여넣어 주세요.")

    draft = st.session_state.label_draft
    if draft is not None:
        if st.session_state.get("ocr_raw_text"):
            with st.expander("이미지에서 읽은 원문", expanded=False):
                st.text(st.session_state["ocr_raw_text"])

        st.markdown("**초안 확인 및 수정**")
        draft_name = st.text_input("제품명", value=draft.product_name, key="draft_name")
        meta_left, meta_right = st.columns(2)
        cat_index = (
            CATEGORIES.index(draft.category) if draft.category in CATEGORIES else 2
        )
        draft_category = meta_left.selectbox(
            "분류", CATEGORIES, index=cat_index, key="draft_category"
        )
        draft_serving = meta_right.number_input(
            "라벨 기준량(g)",
            min_value=0.1,
            max_value=1000.0,
            value=float(draft.serving_basis_g),
            step=1.0,
            key="draft_serving",
        )

        editor_source = pd.DataFrame(
            [{"영양소": n.nutrient, "함량(mg)": n.amount_mg} for n in draft.nutrients]
            or [{"영양소": "", "함량(mg)": 0.0}]
        )
        edited = st.data_editor(
            editor_source,
            num_rows="dynamic",
            hide_index=True,
            width="stretch",
            column_config={
                "함량(mg)": st.column_config.NumberColumn(format="%.4f", min_value=0.0)
            },
            key="draft_editor",
        )
        if draft.unparsed_lines:
            st.caption("확인 불가 줄: " + " · ".join(draft.unparsed_lines))

        draft_complete = st.checkbox(
            "이 라벨의 관련 영양소를 모두 확인했습니다 (미표기 없음)",
            value=draft.label_complete,
            key="draft_complete",
        )

        confirm_col, discard_col = st.columns(2)
        if confirm_col.button("확인하고 조합에 추가"):
            clean_nutrients = [
                DraftNutrient(
                    nutrient=str(row["영양소"]).strip(),
                    amount_mg=_to_float(row["함량(mg)"]),
                    source_text="",
                    source_unit="mg",
                )
                for _, row in edited.iterrows()
                if str(row["영양소"]).strip() and _to_float(row["함량(mg)"]) > 0
            ]
            try:
                rows = draft_to_rows(
                    LabelDraft(
                        product_name=draft_name,
                        category=draft_category,
                        serving_basis_g=float(draft_serving),
                        nutrients=clean_nutrients,
                        label_complete=draft_complete,
                    )
                )
                st.session_state.extra_product_rows.append(rows)
                st.session_state.label_draft = None
                st.session_state.pop("ocr_raw_text", None)
                st.success(
                    f"'{draft_name}' 추가됨. 아래 급여 조합에서 하루 급여량을 지정하세요."
                )
                st.rerun()
            except ValueError as exc:
                st.error(str(exc))
        if discard_col.button("초안 버리기"):
            st.session_state.label_draft = None
            st.session_state.pop("ocr_raw_text", None)
            st.rerun()

    if st.session_state.extra_product_rows:
        added_names = sorted(
            {df["product_name"].iloc[0] for df in st.session_state.extra_product_rows}
        )
        st.caption("추가한 제품: " + ", ".join(added_names))
        if st.button("추가한 제품 모두 제거"):
            st.session_state.extra_product_rows = []
            st.rerun()


# ---------------------------------------------------------------------------
# 제품 카탈로그 (검색 필터 적용)
# ---------------------------------------------------------------------------
_products_parts: list[pd.DataFrame] = [base_products]
if st.session_state.get("extra_product_rows"):
    _products_parts.extend(st.session_state.extra_product_rows)
_loaded = st.session_state.get("loaded_products", [])
if _loaded:
    _products_parts.extend(_loaded)
products = pd.concat(_products_parts, ignore_index=True) if len(_products_parts) > 1 else _products_parts[0]
catalog = products.drop_duplicates("product_id")

# F-005: 제품 검색
search_query = st.text_input(
    "🔍 제품 검색",
    value=st.session_state.get("search_query", ""),
    placeholder="제품명 또는 카테고리 검색 (예: 사료, 영양제, 칼슘)",
    key="search_query_input",
)
st.session_state.search_query = search_query
q = search_query.strip().lower()
if q:
    catalog = catalog[
        catalog["product_name"].str.lower().str.contains(q)
        | catalog["category"].str.lower().str.contains(q)
    ]

# 검색 결과 수 표시 (검색 전에도 전체 제품 수를 항상 표시)
st.caption(
    f"검색 결과: {len(catalog)}개"
    + (f" / 전체 {len(products.drop_duplicates('product_id'))}개" if q else "")
    + f" / 활성 제품 {len(catalog)}개"
)


# ---------------------------------------------------------------------------
# 사이드바: 반려동물 프로필 (F-001 확장: 품종·중성화 추가)
# ---------------------------------------------------------------------------
with st.sidebar:
    st.header("반려동물 프로필")
    pet_name = st.text_input("이름", value="몽이")
    weight = st.number_input("체중(kg)", min_value=1.0, max_value=80.0, value=8.0)
    age = st.number_input("나이", min_value=1, max_value=25, value=5)
    breed = st.text_input("품종", value="", placeholder="예: 말티즈, 푸들")
    neutered = st.checkbox("중성화 완료", value=False)
    st.selectbox("분석 대상", ["성견 데모"])
    st.caption("질환 정보는 MVP 안전 범위에서 추천 판정에 사용하지 않습니다.")

    # F-026: 데이터 저장·복원 패널
    st.divider()
    st.header("💾 데이터 저장·복원")
    st.caption("현재 세션의 프로필·제품·급여량을 SQLite에 저장하거나 불러올 수 있습니다.")
    db_tabs = st.tabs(["저장", "불러오기", "관리"])

    with db_tabs[0]:
        st.info(f"저장 경로: `{DB_PATH}`")
        if st.button("현재 식단을 SQLite에 저장", type="primary"):
            try:
                _save_current_session(pet_name, weight, age, breed, neutered, catalog)
                st.success("저장 완료: 프로필·제품·급여량이 데이터베이스에 기록됐습니다.")
            except Exception as exc:
                st.error(f"저장 실패: {exc}")

    with db_tabs[1]:
        existing_pets = list_pets()
        if not existing_pets:
            st.info("저장된 프로필이 없습니다. '저장' 탭에서 먼저 저장하세요.")
        else:
            pet_options = {f"{p['pet_id']}: {p['name']} ({p['species']}, {p['weight_kg']}kg)": p["pet_id"]
                           for p in existing_pets}
            chosen_label = st.selectbox("불러올 프로필 선택", list(pet_options.keys()))
            chosen_pet_id = pet_options[chosen_label]
            if st.button("선택한 프로필·식단 불러오기"):
                try:
                    pet = get_pet(chosen_pet_id)
                    if pet is None:
                        raise ValueError(f"펫 ID {chosen_pet_id}를 찾을 수 없습니다.")
                    # 프로필 복원 (사이드바 변수는 전역이므로 여기서 갱신해도 다음 실행에 반영됨)
                    st.session_state["pet_name"] = pet["name"]
                    st.session_state["weight"] = pet["weight_kg"]
                    st.session_state["breed"] = pet.get("breed") or ""
                    st.session_state["neutered"] = pet.get("neutered", False)
                    # 식단 복원
                    plan = get_feeding_plan(chosen_pet_id)
                    added_ids: set[str] = set()
                    for entry in plan:
                        pid = entry["product_id"]
                        if pid not in set(catalog["product_id"]):
                            prod = next((p for p in list_products() if p["product_id"] == pid), None)
                            if prod is None:
                                continue
                            st.session_state.setdefault("loaded_products", [])
                            st.session_state.loaded_products.append(
                                pd.DataFrame(
                                    [
                                        {
                                            "product_id": pid,
                                            "product_name": prod["name"],
                                            "category": prod["category"],
                                            "serving_basis_g": prod["serving_basis_g"],
                                            "nutrient": "",
                                            "amount_mg": 0,
                                            "label_complete": prod["label_complete"],
                                            "monthly_price_krw": prod["monthly_price_krw"],
                                        }
                                    ]
                                )
                            )
                            added_ids.add(pid)
                        st.session_state[f"active_{pid}"] = entry["active"]
                        st.session_state[f"amount_{pid}"] = entry["daily_amount_g"]
                    st.success(f"펫 ID {chosen_pet_id}의 식단이 복원됐습니다." + (f" 새로 추가된 제품: {', '.join(sorted(added_ids))}" if added_ids else ""))
                    st.rerun()
                except Exception as exc:
                    st.error(f"불러오기 실패: {exc}")

    with db_tabs[2]:
        st.subheader("저장된 펫 목록")
        if existing_pets:
            pet_df = pd.DataFrame([
                {"ID": p["pet_id"], "이름": p["name"], "종": p["species"],
                 "체중": f"{p['weight_kg']}kg", "생애단계": p["life_stage"],
                 "품종": p["breed"] or "-", "중성화": "예" if p["neutered"] else "아니요"}
                for p in existing_pets
            ])
            st.dataframe(pet_df, hide_index=True, width="stretch")
        else:
            st.info("저장된 펫이 없습니다.")

        st.subheader("저장된 제품 목록")
        existing_products = list_products()
        if existing_products:
            prod_df = pd.DataFrame([
                {"product_id": p["product_id"], "이름": p["name"], "분류": p["category"],
                 "기준량(g)": p["serving_basis_g"], "월 가격(원)": p["monthly_price_krw"],
                 "출처": p["source"], "라벨확인": "완료" if p["label_complete"] else "미완"}
                for p in existing_products
            ])
            st.dataframe(prod_df, hide_index=True, width="stretch")
            if st.button("저장된 제품 전체 삭제"):
                for p in existing_products:
                    delete_product(p["product_id"])
                st.success("저장된 제품을 모두 삭제했습니다.")
                st.rerun()
        else:
            st.info("저장된 제품이 없습니다.")


def _save_current_session(
    pet_name: str,
    weight: float,
    age: int,
    breed: str,
    neutered: bool,
    catalog: pd.DataFrame,
) -> None:
    """현재 화면의 프로필·제품·급여량을 SQLite에 저장한다."""
    species = "dog"
    life_stage = "adult"
    pet_id = create_pet(
        name=pet_name,
        species=species,
        weight_kg=weight,
        life_stage=life_stage,
        breed=(breed or None),
        neutered=neutered,
    )
    # 기존 동일 펫의 식단 삭제 후 새로 저장
    for row in catalog.itertuples():
        active = st.session_state.get(f"active_{row.product_id}", True)
        amount = float(st.session_state.get(f"amount_{row.product_id}", 1.0))
        if not active or amount <= 0:
            continue
        monthly_price = int(row.monthly_price_krw) if not pd.isna(row.monthly_price_krw) else 0
        nutrient_rows = [
            (n["nutrient"], float(n["amount_mg"]), bool(n.get("label_complete", True)))
            for n in (row._asdict().get("nutrients") if hasattr(row, "nutrients") else [])
        ]
        # catalog itertuples는 nutrients 속성이 없으므로 products DataFrame에서 직접 가져온다
        prod_rows = products[products["product_id"] == row.product_id]
        nutrient_pairs = [
            (rn["nutrient"], float(rn["amount_mg"]), bool(rn["label_complete"]))
            for _, rn in prod_rows.iterrows()
        ]
        upsert_product(
            product_id=row.product_id,
            name=row.product_name,
            category=row.category,
            serving_basis_g=float(row.serving_basis_g),
            monthly_price_krw=monthly_price,
            source="app_session",
            label_complete=bool(row.label_complete),
            nutrients=nutrient_pairs,
        )
        set_feeding_plan(pet_id=pet_id, product_id=row.product_id,
                         daily_amount_g=amount, active=active)


st.subheader(f"{pet_name}의 현재 급여 조합")
st.caption("제품을 끄면 변경 전후를 즉시 비교할 수 있습니다.")

defaults = {"food_a": 120.0, "supp_cal": 2.0, "snack_a": 10.0, "multi_a": 3.0}

# 현재 활성 제품들의 선택 상태를 세션에서 불러오거나 기본값 설정
for row in catalog.itertuples():
    st.session_state.setdefault(f"active_{row.product_id}", True)
    if f"amount_{row.product_id}" not in st.session_state:
        st.session_state[f"amount_{row.product_id}"] = defaults.get(
            row.product_id, 1.0
        )

current_selections = _build_selections(catalog)

# 기준선(모든 제품 활성화, 기본 급여량) 계산 — 비교용
baseline_selections = [
    FeedingSelection(row.product_id, float(defaults.get(row.product_id, 1.0)), True)
    for row in catalog.itertuples()
]

intake = calculate_intake(products, current_selections)
summary = summarize_intake(intake, standards)

# F-018: 제품 제외 전후 비교
# 어떤 제품이 비활성화되었는지 확인
inactive_ids = {
    row.product_id
    for row in catalog.itertuples()
    if not st.session_state.get(f"active_{row.product_id}", True)
}

if inactive_ids:
    st.divider()
    st.subheader("제품 제외 전후 비교")
    st.caption(
        "비활성화된 제품이 있을 때, 제외 전(기준선)과 제외 후(현재)의 영양소 총량을 비교합니다."
    )

    # 제외 전: 비활성 제품을 다시 활성화한 상태
    before_selections = []
    for row in catalog.itertuples():
        active = st.session_state.get(f"active_{row.product_id}", True)
        amount = float(st.session_state.get(f"amount_{row.product_id}", 1.0))
        # 기준선에서는 모든 제품을 활성화
        before_selections.append(
            FeedingSelection(row.product_id, amount, active=True)
        )

    before_intake = calculate_intake(products, before_selections)
    before_summary = summarize_intake(before_intake, standards)
    after_summary = summary  # 현재 상태 (비활성 포함)

    comp_left, comp_right = st.columns(2)
    with comp_left:
        st.markdown(
            '<div class="comparison-card before"><h4>✅ 제외 전 (전체 활성화)</h4></div>',
            unsafe_allow_html=True,
        )
        before_display = before_summary[
            ["nutrient", "total_mg", "demo_min_mg", "demo_max_mg", "status"]
        ].copy()
        before_display["상태"] = before_display["status"].map(
            lambda x: f"{status_icon(x)} {x}"
        )
        before_display = before_display.rename(
            columns={
                "nutrient": "영양소",
                "total_mg": "총량(mg)",
                "demo_min_mg": "최소",
                "demo_max_mg": "최대",
            }
        ).drop(columns="status")
        st.dataframe(
            before_display,
            hide_index=True,
            width="stretch",
            column_config={"총량(mg)": st.column_config.NumberColumn(format="%.3f")},
        )

    with comp_right:
        st.markdown(
            '<div class="comparison-card after"><h4>🔴 제외 후 (현재 선택)</h4></div>',
            unsafe_allow_html=True,
        )
        after_display = after_summary[
            ["nutrient", "total_mg", "demo_min_mg", "demo_max_mg", "status"]
        ].copy()
        after_display["상태"] = after_display["status"].map(
            lambda x: f"{status_icon(x)} {x}"
        )
        after_display = after_display.rename(
            columns={
                "nutrient": "영양소",
                "total_mg": "총량(mg)",
                "demo_min_mg": "최소",
                "demo_max_mg": "최대",
            }
        ).drop(columns="status")
        st.dataframe(
            after_display,
            hide_index=True,
            width="stretch",
            column_config={"총량(mg)": st.column_config.NumberColumn(format="%.3f")},
        )

    # 변동액 하이라이트
    st.markdown("**제외로 인한 영양소 변동**")
    diff_df = before_summary[["nutrient", "total_mg"]].merge(
        after_summary[["nutrient", "total_mg"]],
        on="nutrient",
        suffixes=("_전", "_후"),
    )
    diff_df["변동(mg)"] = diff_df["total_mg_후"] - diff_df["total_mg_전"]
    diff_df["변동률(%)"] = (
        diff_df["변동(mg)"] / diff_df["total_mg_전"].replace(0, pd.NA) * 100
    ).fillna(0)
    diff_display = diff_df.rename(
        columns={
            "nutrient": "영양소",
            "total_mg_전": "제외 전(mg)",
            "total_mg_후": "제외 후(mg)",
            "변동(mg)": "변동량(mg)",
            "변동률(%)": "변동률(%)",
        }
    )[["영양소", "제외 전(mg)", "제외 후(mg)", "변동량(mg)", "변동률(%)"]]
    st.dataframe(
        diff_display,
        hide_index=True,
        width="stretch",
        column_config={
            "제외 전(mg)": st.column_config.NumberColumn(format="%.3f"),
            "제외 후(mg)": st.column_config.NumberColumn(format="%.3f"),
            "변동량(mg)": st.column_config.NumberColumn(format="%.3f"),
            "변동률(%)": st.column_config.NumberColumn(format="%.1f"),
        },
    )


# ---------------------------------------------------------------------------
# 제품 카드 렌더링
# ---------------------------------------------------------------------------
cols = st.columns(2)
for index, row in enumerate(catalog.itertuples()):
    with cols[index % 2]:
        with st.container(border=True):
            active = st.checkbox(
                f"{row.product_name} · {row.category}",
                value=True,
                key=f"active_{row.product_id}",
            )
            amount = st.number_input(
                "하루 급여량(g)",
                min_value=0.0,
                max_value=1000.0,
                value=st.session_state.get(f"amount_{row.product_id}", 1.0),
                step=1.0,
                key=f"amount_{row.product_id}",
                disabled=not active,
            )
            # F-020: 일일·월간 비용 표시
            monthly_price = int(row.monthly_price_krw) if not pd.isna(row.monthly_price_krw) else 0
            if monthly_price > 0 and active and amount > 0:
                daily_cost = monthly_price / 30.0 * (amount / row.serving_basis_g)
                monthly_cost = daily_cost * 30.0
                st.caption(
                    f"💰 일일 약 {daily_cost:,.0f}원 · 월 약 {monthly_cost:,.0f}원 "
                    f"(라벨 기준 {row.serving_basis_g}g 기준)"
                )
            st.caption(
                "라벨 전체 확인"
                if bool(row.label_complete)
                else "일부 영양소 미표기 · 분석 신뢰도 제한"
            )


# ---------------------------------------------------------------------------
# 지표
# ---------------------------------------------------------------------------
st.divider()
active_count = sum(s.active for s in current_selections)
warning_count = int(summary["status"].isin(["중복 가능", "기준 초과 가능"]).sum())
incomplete_count = int((summary["status"] == "정보 부족").sum())
m1, m2, m3, m4, m5 = st.columns(5)
m1.metric("활성 제품", f"{active_count}개")
m2.metric("확인 필요", f"{warning_count}개")
m3.metric("정보 부족", f"{incomplete_count}개")
m4.metric("프로필", f"{age}세 · {weight:.1f}kg")
m5.metric("품종", breed if breed else "미입력")


# ---------------------------------------------------------------------------
# 영양소 분석표 + 그래프
# ---------------------------------------------------------------------------
display = summary[
    ["nutrient", "total_mg", "demo_min_mg", "demo_max_mg", "status"]
].copy()
display["상태"] = display["status"].map(lambda x: f"{status_icon(x)} {x}")
display = display.rename(
    columns={
        "nutrient": "영양소",
        "total_mg": "추정 하루 총량(mg)",
        "demo_min_mg": "참고 최소",
        "demo_max_mg": "참고 최대",
    }
).drop(columns="status")

left, right = st.columns([1.15, 1])
with left:
    st.subheader("영양소 분석")
    st.dataframe(
        display,
        hide_index=True,
        width="stretch",
        column_config={"추정 하루 총량(mg)": st.column_config.NumberColumn(format="%.3f")},
    )
    chart = summary.set_index("nutrient")[["range_ratio"]].rename(
        columns={"range_ratio": "참고 최대 대비 비율"}
    )
    st.bar_chart(chart, horizontal=True)

with right:
    st.subheader("제품별 기여도")
    nutrient = st.selectbox("영양소 선택", summary["nutrient"].tolist())
    contribution = product_contributions(intake, nutrient)
    if contribution.empty:
        st.info("선택한 영양소 데이터가 없습니다.")
    else:
        st.bar_chart(contribution.set_index("product_name")["daily_nutrient_mg"])
        top = contribution.iloc[0]
        st.info(
            f"{nutrient} 총량에서 **{top['product_name']}**의 기여도가 "
            f"**{top['share_pct']:.1f}%**로 가장 큽니다."
        )


# ---------------------------------------------------------------------------
# 경고 근거 표시
# ---------------------------------------------------------------------------
st.subheader("근거와 다음 행동")
attention = summary[summary["status"].isin(["중복 가능", "기준 초과 가능", "정보 부족"])]
if attention.empty:
    st.success(
        "현재 입력에서 확인 필요 신호가 없습니다. 이는 실제 영양 적합성을 보장하지 않습니다."
    )
else:
    for row in attention.itertuples():
        if row.status == "정보 부족":
            st.warning(
                f"**{row.nutrient}: 정보 부족** — 제품 라벨 누락값을 0으로 계산하지 않았습니다."
            )
        else:
            contributors = product_contributions(intake, row.nutrient)
            reason = " + ".join(
                f"{x.product_name} {x.share_pct:.0f}%" for x in contributors.itertuples()
            )
            st.warning(
                f"**{row.nutrient}: {row.status}** — 추정 {row.total_mg:.3f} mg/day, "
                f"제품별 기여: {reason}"
            )


# ---------------------------------------------------------------------------
# F-020: 월 예상 비용 비교 패널
# ---------------------------------------------------------------------------
st.divider()
st.subheader("💰 월 예상 비용 비교")

# 제품별 비용 상세
cost_rows = []
for row in catalog.itertuples():
    active = st.session_state.get(f"active_{row.product_id}", True)
    amount = float(st.session_state.get(f"amount_{row.product_id}", 1.0))
    monthly_price = int(row.monthly_price_krw) if not pd.isna(row.monthly_price_krw) else 0
    if monthly_price > 0 and active and amount > 0:
        daily_cost = monthly_price / 30.0 * (amount / row.serving_basis_g)
        monthly_cost = daily_cost * 30.0
        cost_rows.append(
            {
                "제품": row.product_name,
                "분류": row.category,
                "하루 급여량(g)": amount,
                "라벨 기준(g)": row.serving_basis_g,
                "월 가격(원)": monthly_price,
                "일일 비용(원)": round(daily_cost, 0),
                "월간 비용(원)": round(monthly_cost, 0),
            }
        )

if cost_rows:
    cost_df = pd.DataFrame(cost_rows)
    total_daily = cost_df["일일 비용(원)"].sum()
    total_monthly = cost_df["월간 비용(원)"].sum()
    cost_display = cost_df.rename(
        columns={
            "하루 급여량(g)": "하루 급여량",
            "라벨 기준(g)": "라벨 기준",
            "월 가격(원)": "월이 일부만 표시된 가격",
        }
    )
    st.dataframe(
        cost_display,
        hide_index=True,
        width="stretch",
        column_config={
            "일일 비용(원)": st.column_config.NumberColumn(format="%,.0f"),
            "월간 비용(원)": st.column_config.NumberColumn(format="%,.0f"),
        },
    )
    c1, c2 = st.columns(2)
    c1.metric("총 일일 비용", f"{total_daily:,.0f}원")
    c2.metric("총 월간 비용", f"{total_monthly:,.0f}원")
    st.caption(
        "월 비용은 제품 라벨 기준량 대비 실제 하루 급여량 비율로 산정했습니다. "
        "제품별 월 가격은 데모 데이터이며 실제와 다를 수 있습니다."
    )
else:
    st.info("가격이 등록된 활성 제품이 없습니다. 제품 데이터의 월 가격 정보를 확인하세요.")


# ---------------------------------------------------------------------------
# F-022: 결과 리포트 (인쇄/공유용)
# ---------------------------------------------------------------------------
with st.expander("🖨️ 결과 리포트 (인쇄/공유용)", expanded=False):
    st.markdown('<div class="report-card">', unsafe_allow_html=True)
    st.header("우애영 영양 분석 리포트")

    # F-033: 의료 범위 고지 (리포트에도 표시)
    st.warning(
        "본 리포트는 기능 검증용 데모 기준으로 작성되었습니다. "
        "질병 진단·치료·처방 또는 실제 급여 판단에 사용할 수 없습니다.",
        icon="⚠️",
    )

    # 리포트 메타
    col1, col2, col3, col4 = st.columns(4)
    col1.write(f"**반려동물:** {pet_name}")
    col2.write(f"**나이:** {age}세")
    col3.write(f"**체중:** {weight:.1f}kg")
    col4.write(f"**분석 기준:** 성견 데모")

    if breed:
        st.write(f"**품종:** {breed}")
    if neutered:
        st.write("**중성화:** 완료")

    # 활성 제품 요약
    st.subheader("급여 제품")
    active_products = [
        {
            "제품": row.product_name,
            "분류": row.category,
            "하루 급여량(g)": float(st.session_state.get(f"amount_{row.product_id}", 1.0)),
            "라벨 기준(g)": row.serving_basis_g,
        }
        for row in catalog.itertuples()
        if st.session_state.get(f"active_{row.product_id}", True)
    ]
    if active_products:
        ap_df = pd.DataFrame(active_products)
        st.dataframe(ap_df, hide_index=True, width="stretch")
    else:
        st.info("활성 제품이 없습니다.")

    # 영양소 분석표
    st.subheader("영양소 분석 결과")
    report_table = summary[
        ["nutrient", "total_mg", "demo_min_mg", "demo_max_mg", "status", "range_ratio"]
    ].copy()
    report_table["상태 아이콘"] = report_table["status"].map(status_icon)
    report_table["참고 최대 대비"] = report_table["range_ratio"].map(
        lambda x: f"{x:.1%}" if pd.notna(x) else "N/A"
    )
    report_display = report_table.rename(
        columns={
            "nutrient": "영양소",
            "total_mg": "추정 하루 총량(mg)",
            "demo_min_mg": "참고 최소(mg)",
            "demo_max_mg": "참고 최대(mg)",
            "status": "상태",
            "range_ratio": "참고 최대 대비 비율",
        }
    )[["영양소", "추정 하루 총량(mg)", "참고 최소(mg)", "참고 최대(mg)", "상태 아이콘", "상태", "참고 최대 대비"]]
    st.dataframe(
        report_display,
        hide_index=True,
        width="stretch",
        column_config={
            "추정 하루 총량(mg)": st.column_config.NumberColumn(format="%.3f"),
            "참고 최소(mg)": st.column_config.NumberColumn(format="%.3f"),
            "참고 최대(mg)": st.column_config.NumberColumn(format="%.3f"),
        },
    )

    # 경고/주의 사항
    st.subheader("확인 필요 사항")
    attention_report = summary[
        summary["status"].isin(["중복 가능", "기준 초과 가능", "정보 부족"])
    ]
    if attention_report.empty:
        st.success("현재 입력에서 확인 필요 신호가 없습니다.")
    else:
        for row in attention_report.itertuples():
            if row.status == "정보 부족":
                st.warning(
                    f"- **{row.nutrient}**: 정보 부족 — 제품 라벨 누락값을 0으로 계산하지 않았습니다."
                )
            else:
                contributors = product_contributions(intake, row.nutrient)
                reason = " + ".join(
                    f"{x.product_name} ({x.share_pct:.0f}%)"
                    for x in contributors.itertuples()
                )
                st.warning(
                    f"- **{row.nutrient}**: {row.status} — "
                    f"추정 {row.total_mg:.3f} mg/day, 기여: {reason}"
                )

    # 계산 방식
    with st.expander("계산 방식과 데이터 신뢰도"):
        st.write("제품별 하루 영양소량 = 라벨상 함량 × 실제 하루 급여량 ÷ 라벨 기준량")
        st.write("판정은 규칙 엔진이 수행하며, 누락값은 0으로 간주하지 않습니다.")
        st.write("기준표 verified=false: 수의영양학 검토 전 교육용 데모 데이터")

    st.markdown("</div>", unsafe_allow_html=True)
    st.caption("이 화면은 브라우저 인쇄(Ctrl+P / Cmd+P)로 PDF 저장하거나 공유할 수 있습니다.")


# ---------------------------------------------------------------------------
# 계산 방식 설명 (기존 expander 유지)
# ---------------------------------------------------------------------------
with st.expander("계산 방식과 데이터 신뢰도"):
    st.write("제품별 하루 영양소량 = 라벨상 함량 × 실제 하루 급여량 ÷ 라벨 기준량")
    st.write("판정은 규칙 엔진이 수행하며, 누락값은 0으로 간주하지 않습니다.")
    st.write("기준표 verified=false: 수의영양학 검토 전 교육용 데모 데이터")
