package ai.wooaeyoung.units;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** GET /api/units, POST /api/units/convert — 원본 api.py 단위 환산 엔드포인트 포트. */
@RestController
@RequestMapping("/api/units")
public class UnitsController {

    private final Units units;

    public UnitsController(Units units) {
        this.units = units;
    }

    @GetMapping
    public List<String> list() {
        return units.supportedUnits();
    }

    public record ConvertRequest(double value, String unit, String nutrient,
                                  Double servingBasisG, Double servingsPerPack) {}

    @PostMapping("/convert")
    public Map<String, Object> convert(@RequestBody ConvertRequest req) {
        UnitContext ctx = new UnitContext(
                req.servingBasisG() == null ? 100.0 : req.servingBasisG(),
                req.servingsPerPack() == null ? 1.0 : req.servingsPerPack()
        );
        double mg = units.toMgPerServing(req.value(), req.unit(), req.nutrient(), ctx);
        return Map.of("mg_per_serving", mg);
    }

    @ExceptionHandler(UnitError.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(UnitError e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }
}
