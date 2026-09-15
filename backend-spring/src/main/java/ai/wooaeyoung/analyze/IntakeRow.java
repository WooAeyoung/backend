package ai.wooaeyoung.analyze;

public record IntakeRow(
        String nutrient,
        String productId,
        String productName,
        double dailyAmountG,
        double dailyNutrientMg,
        boolean labelComplete
) {}
