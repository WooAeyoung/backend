package ai.wooaeyoung.domain;

import java.util.List;

public record Product(
        String productId,
        String productName,
        String brand,
        String category,
        double servingBasisG,
        int monthlyPriceKrw,
        String source,
        List<NutrientAmount> nutrients
) {}
