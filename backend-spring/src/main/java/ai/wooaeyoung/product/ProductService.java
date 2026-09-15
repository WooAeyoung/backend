package ai.wooaeyoung.product;

import ai.wooaeyoung.domain.NutrientAmount;
import ai.wooaeyoung.domain.SavedProduct;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 관리 제품 CRUD (F-026, 저장·복원). 원본 backend/database.py의
 * upsert_product/get_product/list_product 포트. 데모 카탈로그(CatalogService)와는 별개 테이블이다.
 */
@Service
public class ProductService {

    private static final Set<String> CATEGORIES = Set.of("주식", "간식", "영양제");

    private static final RowMapper<NutrientAmount> NUTRIENT_MAPPER = (rs, i) -> new NutrientAmount(
            rs.getString("nutrient"), rs.getDouble("amount_mg"), rs.getInt("label_complete") != 0);

    private final JdbcTemplate jdbc;

    public ProductService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsertProduct(String productId, String name, String category, double servingBasisG,
                               Integer monthlyPriceKrw, List<NutrientAmount> nutrients) {
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category는 주식/간식/영양제 중 하나여야 합니다");
        }
        if (servingBasisG <= 0) {
            throw new IllegalArgumentException("servingBasisG는 0보다 커야 합니다");
        }
        int price = monthlyPriceKrw == null ? 0 : monthlyPriceKrw;
        jdbc.update(
                "INSERT INTO products(product_id, name, category, serving_basis_g, monthly_price_krw, source, label_complete) " +
                        "VALUES (?,?,?,?,?,?,1) " +
                        "ON CONFLICT(product_id) DO UPDATE SET name=excluded.name, category=excluded.category, " +
                        "serving_basis_g=excluded.serving_basis_g, monthly_price_krw=excluded.monthly_price_krw, " +
                        "source=excluded.source",
                productId, name, category, servingBasisG, price, "api"
        );
        jdbc.update("DELETE FROM product_nutrients WHERE product_id=?", productId);

        // 성분명이 겹치면 첫 값만 남긴다 — 원본 api.py의 upsert 정리 로직과 동일.
        Map<String, NutrientAmount> deduped = new LinkedHashMap<>();
        for (NutrientAmount n : nutrients) {
            String key = n.nutrient() == null ? "" : n.nutrient().trim();
            if (key.isEmpty() || n.amountMg() < 0 || deduped.containsKey(key)) continue;
            deduped.put(key, n);
        }
        for (NutrientAmount n : deduped.values()) {
            jdbc.update(
                    "INSERT INTO product_nutrients(product_id, nutrient, amount_mg, label_complete) VALUES (?,?,?,?)",
                    productId, n.nutrient().trim(), n.amountMg(), n.labelComplete() ? 1 : 0
            );
        }
    }

    public SavedProduct getProduct(String productId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT product_id, name, category, serving_basis_g, monthly_price_krw, source, label_complete " +
                            "FROM products WHERE product_id=?", productId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
        return toProduct(row, nutrientsOf(productId));
    }

    public List<SavedProduct> listProducts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT product_id, name, category, serving_basis_g, monthly_price_krw, source, label_complete " +
                        "FROM products ORDER BY category, name");
        List<SavedProduct> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(toProduct(row, nutrientsOf((String) row.get("product_id"))));
        }
        return out;
    }

    private List<NutrientAmount> nutrientsOf(String productId) {
        return jdbc.query(
                "SELECT nutrient, amount_mg, label_complete FROM product_nutrients WHERE product_id=? ORDER BY nutrient",
                NUTRIENT_MAPPER, productId);
    }

    private SavedProduct toProduct(Map<String, Object> row, List<NutrientAmount> nutrients) {
        return new SavedProduct(
                (String) row.get("product_id"),
                (String) row.get("name"),
                (String) row.get("category"),
                ((Number) row.get("serving_basis_g")).doubleValue(),
                ((Number) row.get("monthly_price_krw")).intValue(),
                (String) row.get("source"),
                ((Number) row.get("label_complete")).intValue() != 0,
                nutrients
        );
    }
}
