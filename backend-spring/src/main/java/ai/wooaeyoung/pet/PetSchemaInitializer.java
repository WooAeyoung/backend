package ai.wooaeyoung.pet;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 원본 backend/database.py의 pets/products/product_nutrients/feeding_plans 테이블 생성(F-025) 포트.
 * users/sessions는 {@link ai.wooaeyoung.auth.SchemaInitializer}가 따로 만든다.
 */
@Component
public class PetSchemaInitializer {

    private final JdbcTemplate jdbc;

    public PetSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pets (
                    pet_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER REFERENCES users(user_id),
                    name TEXT NOT NULL,
                    species TEXT NOT NULL CHECK (species IN ('dog','cat')),
                    birth_date TEXT,
                    weight_kg REAL NOT NULL CHECK (weight_kg > 0),
                    life_stage TEXT NOT NULL,
                    breed TEXT,
                    neutered INTEGER NOT NULL DEFAULT 0 CHECK (neutered IN (0,1))
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    product_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL CHECK (category IN ('주식','간식','영양제')),
                    serving_basis_g REAL NOT NULL CHECK (serving_basis_g > 0),
                    monthly_price_krw INTEGER CHECK (monthly_price_krw >= 0),
                    source TEXT NOT NULL,
                    label_complete INTEGER NOT NULL DEFAULT 1 CHECK (label_complete IN (0,1))
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_nutrients (
                    product_id TEXT NOT NULL REFERENCES products(product_id),
                    nutrient TEXT NOT NULL,
                    amount_mg REAL NOT NULL CHECK (amount_mg >= 0),
                    label_complete INTEGER NOT NULL CHECK (label_complete IN (0,1)),
                    PRIMARY KEY (product_id, nutrient)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS feeding_plans (
                    pet_id INTEGER NOT NULL REFERENCES pets(pet_id),
                    product_id TEXT NOT NULL REFERENCES products(product_id),
                    daily_amount_g REAL NOT NULL CHECK (daily_amount_g >= 0),
                    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
                    PRIMARY KEY (pet_id, product_id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_feeding_plans_pet_active ON feeding_plans(pet_id, active)");
    }
}
