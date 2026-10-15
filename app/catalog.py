from pathlib import Path
from types import MappingProxyType

from .catalog_index import ProductCatalog
from .standards.models import StandardProfileId
from .standards.registry import get_profile, master

# 앱 시작 시 JSON 파일을 한 번 읽고 해시 테이블과 트라이를 메모리에 구성한다.
CATALOG = ProductCatalog.from_json(Path(__file__).parents[1] / "data" / "products.json")
PRODUCTS = CATALOG.products
PRODUCT_BY_ID = CATALOG.by_id

# 기준값은 data/standards에서 읽는다. 아래는 기존 import 호환용 읽기 전용 FEDIAF 2025 보기이며 계산에는 쓰지 않는다.
_FEDIAF = get_profile(StandardProfileId.FEDIAF_2025)
STANDARD_VERSION = _FEDIAF.version
NUTRIENTS = MappingProxyType({
    item.id: MappingProxyType({"name": master(_FEDIAF.id, item.id).nameKo, "unit": item.unit}) for item in _FEDIAF.nutrients
})
STANDARDS = MappingProxyType({
    species: MappingProxyType({
        stage.id: MappingProxyType({
            item.id: MappingProxyType({"minimum": record.min, "upper": record.max})
            for item in _FEDIAF.nutrients
            for record in [_FEDIAF.record(item.id, stage.recordStage)]
        })
        for stage in _FEDIAF.lifeStages if stage.species == species
    })
    for species in ("DOG", "CAT")
})
