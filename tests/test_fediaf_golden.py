"""FEDIAF 2025 결과 회귀 방지. 골든 파일의 모든 키·값은 그대로 유지되어야 하고, 새 필드 추가만 허용한다."""
import json
from pathlib import Path

import pytest

from tests.golden.fediaf_cases import CASES, GET_CASES
from tests.golden.generate_fediaf_golden import OUTPUT, collect

GOLDEN = json.loads(Path(OUTPUT).read_text(encoding="utf-8"))
CURRENT = collect()


def assert_preserved(expected, actual, path="$"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path}: dict가 아님"
        for key, value in expected.items():
            assert key in actual, f"{path}.{key}: 필드가 사라짐"
            assert_preserved(value, actual[key], f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list) and len(actual) == len(expected), f"{path}: 목록 길이가 다름"
        for index, (left, right) in enumerate(zip(expected, actual)):
            assert_preserved(left, right, f"{path}[{index}]")
    else:
        # 부동소수점도 근사 없이 같은 값이어야 한다.
        assert type(actual) is type(expected) and actual == expected, f"{path}: {expected!r} → {actual!r}"


@pytest.mark.parametrize("name", [case[0] for case in CASES] + [case[0] for case in GET_CASES])
def test_fediaf_output_matches_golden(name):
    assert_preserved(GOLDEN[name], CURRENT[name])


def test_golden_covers_every_case():
    assert set(GOLDEN) == {case[0] for case in CASES} | {case[0] for case in GET_CASES}
