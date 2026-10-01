from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Iterable


def normalize(value: str) -> str:
    """Make Korean/Latin product queries comparable without whitespace/punctuation."""
    return re.sub(r"[^0-9a-z가-힣]", "", value.casefold())


class _TrieNode:
    __slots__ = ("children", "product_ids")

    def __init__(self) -> None:
        self.children: dict[str, _TrieNode] = {}
        self.product_ids: list[str] = []


class HashTable:
    """Separate-chaining hash table used for product identity lookup."""

    def __init__(self, size: int = 31) -> None:
        self._buckets: list[list[tuple[str, dict]]] = [[] for _ in range(size)]

    def _slot(self, key: str) -> int:
        value = 0
        for char in key:
            value = (value * 31 + ord(char)) & 0xFFFFFFFF
        return value % len(self._buckets)

    def __setitem__(self, key: str, value: dict) -> None:
        bucket = self._buckets[self._slot(key)]
        for index, (stored_key, _) in enumerate(bucket):
            if stored_key == key:
                bucket[index] = (key, value)
                return
        bucket.append((key, value))

    def get(self, key: str, default=None):
        for stored_key, value in self._buckets[self._slot(key)]:
            if stored_key == key:
                return value
        return default

    def __contains__(self, key: str) -> bool:
        return self.get(key) is not None

    def __getitem__(self, key: str) -> dict:
        value = self.get(key)
        if value is None:
            raise KeyError(key)
        return value


class ProductCatalog:
    """In-memory catalog with O(1) identity lookup and prefix search."""

    def __init__(self, products: Iterable[dict]) -> None:
        self.products = list(products)
        self.by_id, self.by_barcode = HashTable(), HashTable()
        for product in self.products:
            self.by_id[product["id"]] = product
            if product.get("barcode"):
                self.by_barcode[product["barcode"]] = product
        self._root = _TrieNode()
        for product in self.products:
            searchable = {
                normalize(product["name"]),
                normalize(product["brand"]),
                normalize(f'{product["brand"]}{product["name"]}'),
            }
            if product.get("barcode"):
                searchable.add(normalize(product["barcode"]))
            for term in searchable:
                self._insert(term, product["id"])

    @classmethod
    def from_json(cls, path: Path) -> "ProductCatalog":
        with path.open(encoding="utf-8") as source:
            payload = json.load(source)
        products = payload.get("products")
        if not isinstance(products, list):
            raise ValueError("제품 데이터 파일에 products 배열이 필요합니다.")
        return cls(products)

    def _insert(self, term: str, product_id: str) -> None:
        node = self._root
        for char in term:
            node = node.children.setdefault(char, _TrieNode())
            if product_id not in node.product_ids:
                node.product_ids.append(product_id)

    def get(self, identity: str) -> dict | None:
        return self.by_id.get(identity) or self.by_barcode.get(identity)

    def search(self, query: str = "", product_type: str | None = None, limit: int = 10) -> list[dict]:
        needle = normalize(query)
        if not needle:
            candidates = self.products
        elif query in self.by_barcode:
            candidates = [self.by_barcode[query]]
        else:
            node = self._root
            for char in needle:
                node = node.children.get(char)
                if node is None:
                    return []
            candidates = [self.by_id[product_id] for product_id in node.product_ids]
        return [
            product for product in candidates
            if product_type is None or product["type"] == product_type
        ][:limit]
