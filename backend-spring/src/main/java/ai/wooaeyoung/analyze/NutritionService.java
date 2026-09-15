package ai.wooaeyoung.analyze;

import ai.wooaeyoung.domain.NutrientAmount;
import ai.wooaeyoung.domain.NutrientStandard;
import ai.wooaeyoung.domain.Product;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 섭취량 합산·상태 판정·기여도 (F-012/013/016). 원본 backend/nutrition.py 포트.
 * pandas groupby 대신 순수 Java 스트림으로 재구현했다.
 */
@Service
public class NutritionService {

    /** calculate_intake: 선택된 제품·급여량으로 성분별 일일 섭취량(mg)을 계산한다. */
    public List<IntakeRow> calculateIntake(List<Product> catalog, List<FeedingSelection> selections) {
        Map<String, Product> byId = catalog.stream()
                .collect(Collectors.toMap(Product::productId, p -> p, (a, b) -> a));
        List<IntakeRow> out = new ArrayList<>();
        for (FeedingSelection sel : selections) {
            if (!sel.active()) continue;
            if (sel.dailyAmountG() < 0) {
                throw new IllegalArgumentException("하루 급여량은 음수가 될 수 없습니다.");
            }
            Product p = byId.get(sel.productId());
            if (p == null) {
                throw new IllegalArgumentException("알 수 없는 제품: " + sel.productId());
            }
            for (NutrientAmount n : p.nutrients()) {
                double dailyNutrientMg = n.amountMg() * sel.dailyAmountG() / p.servingBasisG();
                out.add(new IntakeRow(n.nutrient(), p.productId(), p.productName(),
                        sel.dailyAmountG(), dailyNutrientMg, n.labelComplete()));
            }
        }
        return out;
    }

    /** summarize_intake: 성분별 총량을 기준표와 비교해 상태를 매긴다. */
    public List<NutrientSummary> summarizeIntake(List<IntakeRow> intake, List<NutrientStandard> standards) {
        Map<String, List<IntakeRow>> byNutrient = intake.stream()
                .collect(Collectors.groupingBy(IntakeRow::nutrient));

        List<NutrientSummary> out = new ArrayList<>();
        for (NutrientStandard std : standards) {
            List<IntakeRow> rows = byNutrient.getOrDefault(std.nutrient(), List.of());
            double totalMg = rows.stream().mapToDouble(IntakeRow::dailyNutrientMg).sum();
            int productCount = (int) rows.stream().map(IntakeRow::productId).distinct().count();
            boolean dataComplete = !rows.isEmpty() && rows.stream().allMatch(IntakeRow::labelComplete);

            String status;
            if (!dataComplete) {
                status = "정보 부족";
            } else if (totalMg > std.demoMaxMg()) {
                status = "기준 초과 가능";
            } else if (productCount >= 2 && totalMg >= std.demoMinMg() * 0.8) {
                status = "중복 가능";
            } else if (totalMg < std.demoMinMg()) {
                status = "참고 범위 미만";
            } else {
                status = "정보 충분";
            }
            double rangeRatio = std.demoMaxMg() == 0 ? 0 : totalMg / std.demoMaxMg();

            out.add(new NutrientSummary(
                    std.nutrient(), std.demoMinMg(), std.demoMaxMg(), std.source(), std.verified(),
                    totalMg, productCount, dataComplete, status, rangeRatio
            ));
        }
        return out;
    }

    /** product_contributions: 특정 성분에 제품별로 얼마나 기여했는지. */
    public List<ProductContribution> productContributions(List<IntakeRow> intake, String nutrient) {
        Map<String, Double> byProduct = intake.stream()
                .filter(r -> r.nutrient().equals(nutrient))
                .collect(Collectors.groupingBy(IntakeRow::productName,
                        LinkedHashMap::new, Collectors.summingDouble(IntakeRow::dailyNutrientMg)));
        double total = byProduct.values().stream().mapToDouble(Double::doubleValue).sum();
        return byProduct.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new ProductContribution(e.getKey(), e.getValue(),
                        total == 0 ? 0 : e.getValue() / total * 100))
                .collect(Collectors.toList());
    }
}
