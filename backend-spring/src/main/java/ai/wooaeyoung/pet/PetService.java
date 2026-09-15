package ai.wooaeyoung.pet;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Statement;
import java.util.List;
import java.util.Set;

/** 반려동물 프로필 CRUD (F-026). 원본 backend/database.py의 create_pet/get_pet/list_pets 포트. */
@Service
public class PetService {

    private static final Set<String> SPECIES = Set.of("dog", "cat");

    private static final RowMapper<Pet> MAPPER = (rs, i) -> new Pet(
            rs.getLong("pet_id"),
            rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
            rs.getString("name"),
            rs.getString("species"),
            rs.getString("birth_date"),
            rs.getDouble("weight_kg"),
            rs.getString("life_stage"),
            rs.getString("breed"),
            rs.getInt("neutered") != 0
    );

    private static final String SELECT =
            "SELECT pet_id, user_id, name, species, birth_date, weight_kg, life_stage, breed, neutered FROM pets";

    private final JdbcTemplate jdbc;

    public PetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createPet(String name, String species, double weightKg, String lifeStage,
                           String birthDate, String breed, boolean neutered, Long userId) {
        if (!SPECIES.contains(species)) {
            throw new IllegalArgumentException("species는 dog 또는 cat이어야 합니다");
        }
        if (weightKg <= 0) {
            throw new IllegalArgumentException("weightKg는 0보다 커야 합니다");
        }
        // last_insert_rowid()는 SQLite 커넥션 단위라, 풀에서 다른 커넥션을 받으면 어긋난다 —
        // INSERT와 키 조회를 같은 스테이트먼트/커넥션으로 묶는 GeneratedKeyHolder를 쓴다.
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO pets(user_id, name, species, birth_date, weight_kg, life_stage, breed, neutered) " +
                            "VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, userId);
            ps.setString(2, name);
            ps.setString(3, species);
            ps.setObject(4, birthDate);
            ps.setDouble(5, weightKg);
            ps.setString(6, lifeStage);
            ps.setObject(7, breed);
            ps.setInt(8, neutered ? 1 : 0);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** viewerUserId가 있고, 조회된 펫이 다른 사용자 소유면 감춘다(null 반환) — 원본 get_pet과 동일 규칙. */
    public Pet getPet(long petId, Long viewerUserId) {
        Pet pet;
        try {
            pet = jdbc.queryForObject(SELECT + " WHERE pet_id=?", MAPPER, petId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
        if (viewerUserId != null && pet.userId() != null && !pet.userId().equals(viewerUserId)) {
            return null;
        }
        return pet;
    }

    /** userId가 없으면 전체, 있으면 본인 소유 + 주인 없는(공용) 펫만 반환 — 원본 list_pets와 동일 규칙. */
    public List<Pet> listPets(Long userId) {
        if (userId == null) {
            return jdbc.query(SELECT + " ORDER BY pet_id", MAPPER);
        }
        return jdbc.query(SELECT + " WHERE user_id=? OR user_id IS NULL ORDER BY pet_id", MAPPER, userId);
    }
}
