"""기준 프로필 데이터 모델. 모두 frozen이라 적재 뒤에는 바꿀 수 없다."""
from __future__ import annotations

import re
from collections.abc import Mapping
from datetime import date
from enum import Enum
from types import MappingProxyType
from typing import Annotated, TypeVar

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, PrivateAttr, WrapSerializer, field_validator

UNITS = ("G", "MG", "UG", "IU")
NUTRIENT_ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")


class StandardProfileId(str, Enum):
    FEDIAF_2025 = "FEDIAF_2025"
    NIAS_V3 = "NIAS_V3"


class VerificationStatus(str, Enum):
    VERIFIED = "VERIFIED"
    UNVERIFIED = "UNVERIFIED"
    NOT_APPLICABLE = "NOT_APPLICABLE"


def _read_only(value: dict) -> Mapping:
    return MappingProxyType(value)


# 적재 뒤 내용까지 바꿀 수 없는 dict. 프로필끼리 가변 상태를 공유하지 않게 한다.
K = TypeVar("K")
V = TypeVar("V")
ReadOnlyDict = Annotated[dict[K, V], AfterValidator(_read_only), WrapSerializer(lambda value, handler: handler(dict(value)))]


# 기준값은 양수만 허용한다. 빈칸이 0·NaN으로 들어오는 경로를 적재 단계에서 막는다.
StandardNumber = Annotated[float, Field(gt=0, allow_inf_nan=False)]


class Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", validate_default=True)


class NutrientMaster(Frozen):
    id: str
    nameKo: str
    nameEn: str
    category: str
    baseUnit: str
    sortOrder: int
    prioritySupported: bool = False
    # 이 성분에만 쓰는 단위 환산: 1 {키 단위} = 값 {baseUnit}. 예) 비타민 D 1 UG = 40 IU
    unitConversions: ReadOnlyDict[str, float] = {}
    # 특정 프로필 전용 성분(예: FEDIAF의 총 오메가3). 비어 있으면 44개 공통 마스터.
    profiles: tuple[StandardProfileId, ...] = ()


class NutrientCatalog(Frozen):
    nutrients: tuple[NutrientMaster, ...]
    profileSpecific: tuple[NutrientMaster, ...]
    # data/products.json 제품 함량의 단위(기존 FEDIAF 계산 단위와 같다)
    catalogUnits: ReadOnlyDict[str, str]


class StandardRecord(Frozen):
    """기준값 레코드 하나 = (profile, nutrientId, lifeStage). 빈칸은 None으로 보존한다."""
    profile: StandardProfileId
    nutrientId: str
    lifeStage: str
    min: StandardNumber | None
    max: StandardNumber | None
    unit: str
    basis: str
    sourceDocument: str
    sourceTable: str | None
    sourceFootnote: str | None
    verificationStatus: VerificationStatus
    verifiedBy: str | None
    verifiedAt: date | None


class Param(Frozen):
    """계산 매개변수 하나와 그 근거. value가 None이면 값 미확정."""
    value: float | None
    verificationStatus: VerificationStatus
    basis: str
    source: str | None = None


class ProfileNutrient(Frozen):
    id: str
    unit: str


class LifeStage(Frozen):
    id: str
    species: str
    recordStage: str


class GrowthCurve(Frozen):
    a: Param
    b: Param
    adultWeightKgAbove: float
    adultWeightKgAtMost: float | None


class StandardProfile(Frozen):
    id: StandardProfileId
    displayName: str
    publisher: str
    documentName: str
    version: str
    basis: str
    sourceText: str
    model: str
    nutrients: tuple[ProfileNutrient, ...]
    lifeStages: tuple[LifeStage, ...]
    params: ReadOnlyDict[str, Param]
    growthCurves: ReadOnlyDict[str, GrowthCurve] = {}
    records: tuple[StandardRecord, ...] = ()

    @field_validator("model")
    @classmethod
    def known_model(cls, value: str) -> str:
        if value not in {"FEDIAF_2025", "NIAS_V3"}:
            raise ValueError(f"알 수 없는 계산 모델: {value}")
        return value

    def param(self, name: str) -> float:
        value = self.params[name].value
        if value is None:
            raise KeyError(f"{self.id.value} 매개변수 {name} 값이 비어 있습니다.")
        return value

    def stage(self, species: str, stage_id: str) -> LifeStage:
        return next(stage for stage in self.lifeStages if stage.species == species and stage.id == stage_id)

    def record_stages(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(stage.recordStage for stage in self.lifeStages))

    _record_index: Mapping[tuple[str, str], StandardRecord] = PrivateAttr()

    def model_post_init(self, __context) -> None:
        # 읽기 전용 해시 색인: (성분, 기준표 생애주기) → 레코드
        self._record_index = MappingProxyType({(record.nutrientId, record.lifeStage): record for record in self.records})

    def record(self, nutrient_id: str, record_stage: str) -> StandardRecord:
        return self._record_index[(nutrient_id, record_stage)]
