package ai.wooaeyoung.feeding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 급여 계획(식단) CRUD. 원본 backend/database.py의 feeding_plans 테이블 포트. */
@Service
public class FeedingPlanService {

    private static final RowMapper<FeedingPlanEntry> MAPPER = (rs, i) -> new FeedingPlanEntry(
            rs.getString("product_id"), rs.getDouble("daily_amount_g"), rs.getInt("active") != 0);

    private final JdbcTemplate jdbc;

    public FeedingPlanService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setFeedingPlan(long petId, String productId, double dailyAmountG, boolean active) {
        jdbc.update(
                "INSERT INTO feeding_plans(pet_id, product_id, daily_amount_g, active) VALUES (?,?,?,?) " +
                        "ON CONFLICT(pet_id, product_id) DO UPDATE SET " +
                        "daily_amount_g=excluded.daily_amount_g, active=excluded.active",
                petId, productId, dailyAmountG, active ? 1 : 0
        );
    }

    public List<FeedingPlanEntry> getFeedingPlan(long petId) {
        return jdbc.query(
                "SELECT product_id, daily_amount_g, active FROM feeding_plans WHERE pet_id=? ORDER BY product_id",
                MAPPER, petId);
    }

    public record FeedingPlanEntry(String productId, double dailyAmountG, boolean active) {}
}
