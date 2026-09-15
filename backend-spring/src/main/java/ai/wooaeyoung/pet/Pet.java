package ai.wooaeyoung.pet;

/** 반려동물 프로필. 원본 backend/database.py의 pets 테이블 포트. */
public record Pet(
        long petId,
        Long userId,
        String name,
        String species,
        String birthDate,
        double weightKg,
        String lifeStage,
        String breed,
        boolean neutered
) {}
