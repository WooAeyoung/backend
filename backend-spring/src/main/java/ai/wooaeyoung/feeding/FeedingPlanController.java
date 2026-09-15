package ai.wooaeyoung.feeding;

import ai.wooaeyoung.pet.Pet;
import ai.wooaeyoung.pet.PetService;
import ai.wooaeyoung.product.ProductService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** /api/pets/{petId}/feeding — 원본 api.py 식단(급여 계획) 엔드포인트(F-026) 포트. */
@RestController
@RequestMapping("/api/pets/{petId}/feeding")
public class FeedingPlanController {

    private final FeedingPlanService plans;
    private final PetService pets;
    private final ProductService products;

    public FeedingPlanController(FeedingPlanService plans, PetService pets, ProductService products) {
        this.plans = plans;
        this.pets = pets;
        this.products = products;
    }

    public record SetFeedingRequest(@NotBlank String productId, double dailyAmountG, Boolean active) {}

    @PostMapping
    public ResponseEntity<?> set(@PathVariable long petId, @RequestBody SetFeedingRequest req) {
        if (req.dailyAmountG() < 0) {
            return ResponseEntity.badRequest().body(Map.of("detail", "dailyAmountG는 음수가 될 수 없습니다"));
        }
        Pet pet = pets.getPet(petId, null);
        if (pet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "펫을 찾을 수 없습니다"));
        }
        if (products.getProduct(req.productId()) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "제품을 찾을 수 없습니다"));
        }
        boolean active = req.active() == null || req.active();
        plans.setFeedingPlan(petId, req.productId(), req.dailyAmountG(), active);
        return ResponseEntity.ok(Map.of("petId", petId, "productId", req.productId(), "active", active));
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable long petId) {
        if (pets.getPet(petId, null) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "펫을 찾을 수 없습니다"));
        }
        return ResponseEntity.ok(plans.getFeedingPlan(petId));
    }
}
