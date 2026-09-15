package ai.wooaeyoung.product;

import ai.wooaeyoung.domain.NutrientAmount;
import ai.wooaeyoung.domain.SavedProduct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * /api/products — 원본 api.py 제품 CRUD(F-026, 저장·복원) 포트.
 * 데모 카탈로그(/api/catalog/products, CatalogService)와는 별개의 사용자 관리 데이터다.
 * 원본은 쿼리 파라미터로 받았지만, 이 포트는 다른 엔드포인트와 일관되게 JSON 바디로 받는다.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    public record NutrientInput(@NotBlank String nutrient, double amountMg, Boolean labelComplete) {
        NutrientAmount toDomain() {
            return new NutrientAmount(nutrient, amountMg, labelComplete == null || labelComplete);
        }
    }

    public record UpsertRequest(
            @NotBlank String productId,
            @NotBlank String name,
            @NotBlank String category,
            double servingBasisG,
            Integer monthlyPriceKrw,
            List<@Valid NutrientInput> nutrients
    ) {}

    @PostMapping
    public Map<String, Object> upsert(@Valid @RequestBody UpsertRequest req) {
        List<NutrientAmount> nutrients = req.nutrients() == null
                ? List.of()
                : req.nutrients().stream().map(NutrientInput::toDomain).toList();
        products.upsertProduct(req.productId(), req.name(), req.category(), req.servingBasisG(),
                req.monthlyPriceKrw(), nutrients);
        return Map.of("productId", req.productId(), "ok", true);
    }

    @GetMapping
    public List<SavedProduct> list() {
        return products.listProducts();
    }

    @GetMapping("/{productId}")
    public ResponseEntity<?> get(@PathVariable String productId) {
        SavedProduct p = products.getProduct(productId);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "제품을 찾을 수 없습니다"));
        }
        return ResponseEntity.ok(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }
}
