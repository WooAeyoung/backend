"""기준 프로필 레지스트리. 시작 시 한 번 적재하고 이후에는 읽기만 한다."""
from __future__ import annotations

import os
from types import MappingProxyType

from .loader import load_catalog, load_profile, nutrients_for
from .models import NutrientMaster, StandardProfile, StandardProfileId

DEFAULT_PROFILE = StandardProfileId.FEDIAF_2025

NUTRIENT_CATALOG = load_catalog()
PROFILES = MappingProxyType({profile_id: load_profile(profile_id, NUTRIENT_CATALOG) for profile_id in StandardProfileId})
_NUTRIENTS = MappingProxyType({profile_id: MappingProxyType(nutrients_for(NUTRIENT_CATALOG, profile_id)) for profile_id in StandardProfileId})


def get_profile(profile_id: StandardProfileId | str | None = None) -> StandardProfile:
    return PROFILES[StandardProfileId(profile_id or DEFAULT_PROFILE)]


def master_nutrients(profile_id: StandardProfileId) -> MappingProxyType:
    """공통 44개 + 해당 프로필 전용 성분. 키는 영양소 ID."""
    return _NUTRIENTS[profile_id]


def master(profile_id: StandardProfileId, nutrient_id: str) -> NutrientMaster:
    return _NUTRIENTS[profile_id][nutrient_id]


def allow_unverified_standards() -> bool:
    """ALLOW_UNVERIFIED_STANDARDS=true일 때만 미검증 기준값을 판정에 쓴다(테스트·개발용). 요청마다 읽는다."""
    return os.getenv("ALLOW_UNVERIFIED_STANDARDS", "false").strip().lower() in {"1", "true", "yes"}
