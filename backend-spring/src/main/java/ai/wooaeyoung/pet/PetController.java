package ai.wooaeyoung.pet;

import ai.wooaeyoung.auth.AuthService;
import ai.wooaeyoung.auth.BearerToken;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * /api/pets — 원본 api.py 펫 CRUD(F-026) 포트.
 * 로그인 상태면(Authorization: Bearer) 생성한 펫을 해당 사용자에 연결하고, 목록 조회 시
 * 본인 펫 + 주인 없는 펫만 보인다. 로그인하지 않아도 그대로 동작한다(데스크톱 로컬 사용 지원).
 */
@RestController
@RequestMapping("/api/pets")
public class PetController {

    private final PetService pets;
    private final AuthService auth;

    public PetController(PetService pets, AuthService auth) {
        this.pets = pets;
        this.auth = auth;
    }

    public record CreatePetRequest(
            @NotBlank String name,
            String species,
            Double weightKg,
            String lifeStage,
            String birthDate,
            String breed,
            Boolean neutered
    ) {}

    private Long optionalUserId(String authHeader) {
        Map<String, Object> user = auth.userForToken(BearerToken.from(authHeader));
        return user == null ? null : ((Number) user.get("user_id")).longValue();
    }

    @GetMapping
    public List<Pet> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        return pets.listPets(optionalUserId(authHeader));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody CreatePetRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        long petId = pets.createPet(
                req.name(),
                req.species() == null ? "dog" : req.species(),
                req.weightKg() == null ? 8.0 : req.weightKg(),
                req.lifeStage() == null ? "adult" : req.lifeStage(),
                req.birthDate(),
                req.breed(),
                req.neutered() != null && req.neutered(),
                optionalUserId(authHeader)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("petId", petId));
    }

    @GetMapping("/{petId}")
    public ResponseEntity<?> get(@PathVariable long petId) {
        Pet pet = pets.getPet(petId, null);
        if (pet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "펫을 찾을 수 없습니다"));
        }
        return ResponseEntity.ok(pet);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }
}
