package ai.wooaeyoung.domain;

import java.util.List;

/**
 * SQLite {@code products}/{@code product_nutrients} 테이블에 저장되는 사용자 관리 제품(F-026, 저장·복원).
 * {@code data/products.csv} 기반 데모 카탈로그({@link Product}, /api/catalog/products)와는 별개 데이터다.
 */
public record SavedProduct(
        String productId,
        String name,
        String category,
        double servingBasisG,
        int monthlyPriceKrw,
        String source,
        boolean labelComplete,
        List<NutrientAmount> nutrients
) {
    /** 분석 엔진(NutritionService)은 데모 카탈로그의 {@link Product} 형태를 받으므로 변환한다. */
    public Product toCatalogProduct() {
        return new Product(productId, name, null, category, servingBasisG, monthlyPriceKrw, source, nutrients);
    }
}
