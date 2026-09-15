package ai.wooaeyoung.catalog;

import ai.wooaeyoung.domain.NutrientStandard;
import ai.wooaeyoung.domain.Product;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** GET /api/catalog/products, /api/catalog/standards — 원본 api.py 카탈로그 엔드포인트 포트. */
@RestController
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/catalog/products")
    public List<Product> products() {
        return catalog.products();
    }

    @GetMapping("/api/catalog/standards")
    public List<NutrientStandard> standards() {
        return catalog.standards();
    }
}
