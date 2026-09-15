package ai.wooaeyoung.domain;

public record NutrientStandard(
        String nutrient,
        double demoMinMg,
        double demoMaxMg,
        String unit,
        String source,
        String sourceUrl,
        String version,
        String basis,
        boolean verified
) {}
