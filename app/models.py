from __future__ import annotations

from enum import Enum
from typing import Literal
from pydantic import BaseModel, Field, model_validator

class Species(str, Enum):
    DOG = "DOG"
    CAT = "CAT"

class ProductType(str, Enum):
    FEED = "FEED"
    SUPPLEMENT = "SUPPLEMENT"

class Age(BaseModel):
    value: float = Field(gt=0)
    unit: Literal["WEEK", "MONTH"]

    @property
    def days(self) -> int:
        return round(self.value * (7 if self.unit == "WEEK" else 30.4375))

class Profile(BaseModel):
    name: str = Field(default="우리 아이", min_length=1, max_length=30)
    species: Species
    weightKg: float = Field(gt=0, le=100)
    age: Age
    adultSize: Literal["S", "M", "L", "XL", "XXL"] | None = None
    expectedAdultWeightKg: float | None = Field(default=None, gt=0, le=100)
    completeFeed: bool = True

    @model_validator(mode="after")
    def validate_profile(self):
        if self.age.days < 56:
            raise ValueError("8주 미만 개체는 현재 지원하지 않습니다.")
        if self.species == Species.DOG and self.age.days < 365:
            if self.expectedAdultWeightKg is None or self.expectedAdultWeightKg < self.weightKg:
                raise ValueError("12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.")
        return self

class FeedingItem(BaseModel):
    productId: str
    dailyAmount: float = Field(gt=0)
    unit: str

class ManualNutrient(BaseModel):
    nutrientId: str
    amount: float = Field(ge=0)
    unit: str

class ManualItem(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    type: ProductType
    servingAmount: float = Field(gt=0)
    servingUnit: str
    dailyAmount: float = Field(gt=0)
    nutrients: list[ManualNutrient] = Field(min_length=1)

class AnalysisRequest(BaseModel):
    profile: Profile
    items: list[FeedingItem] = []
    manualItems: list[ManualItem] = []

    @model_validator(mode="after")
    def require_item(self):
        if not self.items and not self.manualItems:
            raise ValueError("분석할 사료 또는 영양제를 하나 이상 추가해주세요.")
        if len(self.items) + len(self.manualItems) > 20:
            raise ValueError("제품은 최대 20개까지 분석할 수 있습니다.")
        return self

class RecommendationRequest(AnalysisRequest):
    maxItems: int = Field(default=3, ge=1, le=3)

