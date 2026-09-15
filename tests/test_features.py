import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from backend.api import build_app
from backend.database import init_db
from backend.nutrients import normalize_nutrient_name
from backend.nutrition import summarize_intake
from backend.units import UnitContext, UnitError, to_mg_per_serving

client = TestClient(build_app())


class NutrientNormalizationTests(unittest.TestCase):
    """F-032: 성분명 정규화."""

    def test_aliases_map_to_canonical(self):
        self.assertEqual(normalize_nutrient_name("Ca"), "칼슘")
        self.assertEqual(normalize_nutrient_name("calcium"), "칼슘")
        self.assertEqual(normalize_nutrient_name("칼슘(Ca)"), "칼슘")
        self.assertEqual(normalize_nutrient_name("Vit D3"), "비타민D")
        self.assertEqual(normalize_nutrient_name("zinc"), "아연")
        self.assertEqual(normalize_nutrient_name("Se"), "셀레늄")
        self.assertEqual(normalize_nutrient_name("crude protein"), "조단백")

    def test_unknown_passes_through(self):
        self.assertEqual(normalize_nutrient_name("특수성분X"), "특수성분X")


class UnitConversionTests(unittest.TestCase):
    """F-011: 다중 단위 표준화."""

    def test_mass_units(self):
        self.assertEqual(to_mg_per_serving(1.5, "g", "칼슘"), 1500)
        self.assertAlmostEqual(to_mg_per_serving(18, "mcg", "비타민D"), 0.018)

    def test_percent_needs_serving(self):
        # 100g 기준량의 1.2% = 1200 mg
        self.assertAlmostEqual(
            to_mg_per_serving(1.2, "%", "칼슘", UnitContext(serving_basis_g=100)), 1200
        )

    def test_mg_per_kg_ppm(self):
        self.assertAlmostEqual(
            to_mg_per_serving(80, "mg/kg", "아연", UnitContext(serving_basis_g=100)), 8.0
        )

    def test_iu_per_kg_vitamin_d(self):
        got = to_mg_per_serving(500, "IU/kg", "비타민D", UnitContext(serving_basis_g=1000))
        self.assertAlmostEqual(got, 500 * 2.5e-5)

    def test_mg_per_tablet(self):
        got = to_mg_per_serving(
            200, "mg/정", "칼슘", UnitContext(servings_per_pack=2)
        )
        self.assertEqual(got, 400)

    def test_unknown_unit_raises(self):
        with self.assertRaises(UnitError):
            to_mg_per_serving(1, "스푼", "칼슘")

    def test_endpoint(self):
        r = client.post(
            "/api/units/convert",
            json={"value": 1.2, "unit": "%", "nutrient": "칼슘", "serving_basis_g": 100},
        )
        self.assertEqual(r.status_code, 200)
        self.assertAlmostEqual(r.json()["mg"], 1200)


class BoundaryClassificationTests(unittest.TestCase):
    """F-013: 상태 판정 경계값."""

    def _summ(self, total, mn, mx, complete=True, count=1):
        import pandas as pd

        intake = pd.DataFrame(
            [
                {
                    "nutrient": "칼슘",
                    "product_id": f"p{i}",
                    "product_name": f"P{i}",
                    "daily_nutrient_mg": total / count,
                    "label_complete": complete,
                }
                for i in range(count)
            ]
        )
        std = pd.DataFrame([{"nutrient": "칼슘", "demo_min_mg": mn, "demo_max_mg": mx}])
        return summarize_intake(intake, std).iloc[0]["status"]

    def test_exactly_at_max_is_not_over(self):
        self.assertNotEqual(self._summ(1500, 1000, 1500), "기준 초과 가능")

    def test_just_over_max(self):
        self.assertEqual(self._summ(1500.01, 1000, 1500), "기준 초과 가능")

    def test_below_min(self):
        self.assertEqual(self._summ(999, 1000, 1500), "참고 범위 미만")

    def test_incomplete_label_wins(self):
        self.assertEqual(self._summ(1200, 1000, 1500, complete=False), "정보 부족")

    def test_two_products_near_min_is_duplicate(self):
        self.assertEqual(self._summ(900, 1000, 1500, count=2), "중복 가능")


class PricingTests(unittest.TestCase):
    """F-021: 최저가."""

    def test_lowest_offer(self):
        r = client.get("/api/prices/food_a")
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertGreaterEqual(len(body["offers"]), 1)
        prices = [o["price_krw"] for o in body["offers"]]
        self.assertEqual(body["lowest_price_krw"], min(prices))

    def test_unknown_product(self):
        r = client.get("/api/prices/does_not_exist")
        self.assertEqual(r.json()["offers"], [])


class RecommendTests(unittest.TestCase):
    """F-019: 대체 제품 추천."""

    def test_suggests_swap_when_over_max(self):
        catalog = client.get("/api/catalog/products").json()
        # 칼슘 과잉을 유도: 사료 + 칼슘 영양제를 많이
        picks = {p["product_id"]: p for p in catalog}
        products = [picks["food_a"], picks["supp_cal"]]
        selections = [
            {"product_id": "food_a", "daily_amount_g": 300, "active": True},
            {"product_id": "supp_cal", "daily_amount_g": 8, "active": True},
        ]
        r = client.post(
            "/api/recommend", json={"products": products, "selections": selections}
        )
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertIn("칼슘", body["flagged_nutrients"])
        self.assertIsInstance(body["suggestions"], list)


class AuthTests(unittest.TestCase):
    """F-028: 사용자 인증 + 데이터 분리."""

    def setUp(self):
        self.db = str(Path(tempfile.mkdtemp()) / "auth.db")
        init_db(Path(self.db))

    def _q(self, path):
        return f"{path}?db={self.db}" if "?" not in path else f"{path}&db={self.db}"

    def test_register_login_me_logout(self):
        reg = client.post(
            self._q("/api/auth/register"),
            json={"email": "a@wooaeyoung.test", "password": "supersecret1"},
        )
        self.assertEqual(reg.status_code, 201)
        token = reg.json()["token"]
        me = client.get(self._q("/api/auth/me"), headers={"Authorization": f"Bearer {token}"})
        self.assertEqual(me.status_code, 200)
        self.assertEqual(me.json()["email"], "a@wooaeyoung.test")
        out = client.post(self._q("/api/auth/logout"), headers={"Authorization": f"Bearer {token}"})
        self.assertEqual(out.status_code, 200)
        me2 = client.get(self._q("/api/auth/me"), headers={"Authorization": f"Bearer {token}"})
        self.assertEqual(me2.status_code, 401)

    def test_bad_password(self):
        client.post(
            self._q("/api/auth/register"),
            json={"email": "b@wooaeyoung.test", "password": "supersecret1"},
        )
        bad = client.post(
            self._q("/api/auth/login"),
            json={"email": "b@wooaeyoung.test", "password": "wrongpass1"},
        )
        self.assertEqual(bad.status_code, 401)

    def test_short_password_rejected(self):
        r = client.post(
            self._q("/api/auth/register"),
            json={"email": "c@wooaeyoung.test", "password": "short"},
        )
        self.assertEqual(r.status_code, 400)

    def test_pets_are_user_scoped(self):
        t1 = client.post(
            self._q("/api/auth/register"),
            json={"email": "u1@wooaeyoung.test", "password": "supersecret1"},
        ).json()["token"]
        t2 = client.post(
            self._q("/api/auth/register"),
            json={"email": "u2@wooaeyoung.test", "password": "supersecret1"},
        ).json()["token"]
        save = client.post(
            self._q("/api/session/save"),
            headers={"Authorization": f"Bearer {t1}"},
            json={
                "profile": {"name": "u1개", "weight_kg": 8, "age": 3, "breed": "", "neutered": False},
                "products": [
                    {
                        "product_id": "food_a", "name": "사료", "category": "주식",
                        "serving_basis_g": 100, "monthly_price_krw": 0, "label_complete": True,
                        "nutrients": [{"nutrient": "칼슘", "amount_mg": 1000, "label_complete": True}],
                    }
                ],
                "selections": [{"product_id": "food_a", "daily_amount_g": 100, "active": True}],
            },
        )
        self.assertEqual(save.status_code, 201)
        pet_id = save.json()["pet_id"]
        mine = client.get(self._q("/api/pets"), headers={"Authorization": f"Bearer {t1}"}).json()
        self.assertTrue(any(p["pet_id"] == pet_id for p in mine))
        others = client.get(self._q("/api/pets"), headers={"Authorization": f"Bearer {t2}"}).json()
        self.assertFalse(any(p["pet_id"] == pet_id for p in others))
        blocked = client.get(
            self._q(f"/api/session/restore/{pet_id}"),
            headers={"Authorization": f"Bearer {t2}"},
        )
        self.assertEqual(blocked.status_code, 404)


if __name__ == "__main__":
    unittest.main()
