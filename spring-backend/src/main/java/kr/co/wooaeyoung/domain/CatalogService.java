package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.ds.HashTable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class CatalogService {
    public record DoseBand(Double maxKg, double dose) {}
    public record Product(String id, String barcode, String name, String brand, String type,
                          double servingAmount, String servingUnit, String dataQuality,
                          Map<String, Double> nutrients, Double recommendedDailyAmount,
                          List<DoseBand> doseByWeight) {
        public double doseForWeight(double weightKg) {
            if (doseByWeight != null && !doseByWeight.isEmpty()) {
                return doseByWeight.stream()
                        .filter(band -> band.maxKg() == null || weightKg <= band.maxKg())
                        .findFirst().map(DoseBand::dose)
                        .orElse(doseByWeight.get(doseByWeight.size() - 1).dose());
            }
            if (recommendedDailyAmount == null) throw new IllegalStateException("제품 권장량이 없습니다: " + id);
            return recommendedDailyAmount;
        }
    }

    /** products.json의 nutrients 값 단위(기존 FEDIAF 응답 단위). 제품 데이터에 단위 필드가 없어 여기서 고정한다. */
    public static final Map<String, String> NUTRIENT_UNITS = Map.of(
            "CALCIUM", "MG", "PHOSPHORUS", "MG", "VITAMIN_D", "UG", "VITAMIN_E", "MG", "OMEGA3", "MG", "ZINC", "MG");

    private final List<Product> products;
    private final HashTable<String, Product> productsById;

    public CatalogService(ObjectMapper mapper) throws IOException {
        Map<String, List<Product>> root = mapper.readValue(
                new ClassPathResource("products.json").getInputStream(), new TypeReference<>() {});
        products = root.getOrDefault("products", List.of()).stream().map(product -> new Product(
                product.id(), product.barcode(), product.name(), product.brand(), product.type(),
                product.servingAmount(), product.servingUnit(), product.dataQuality(), product.nutrients(),
                product.recommendedDailyAmount(), product.doseByWeight() == null ? List.of() : product.doseByWeight()))
                .toList();
        HashTable<String, Product> indexed = new HashTable<>();
        products.forEach(product -> indexed.put(product.id(), product));
        productsById = indexed;
    }

    public List<Product> all() { return products; }

    public Product require(String id) {
        Product product = productsById.get(id);
        if (product == null) throw new AnalysisException("제품을 찾을 수 없습니다: " + id);
        return product;
    }

    public List<Product> search(String query, String type, int limit) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return products.stream()
                .filter(product -> type == null || type.isBlank() || product.type().equalsIgnoreCase(type))
                .filter(product -> needle.isBlank()
                        || product.id().toLowerCase(Locale.ROOT).contains(needle)
                        || product.name().toLowerCase(Locale.ROOT).contains(needle)
                        || product.brand().toLowerCase(Locale.ROOT).contains(needle)
                        || (product.barcode() != null && product.barcode().contains(needle)))
                .limit(limit)
                .toList();
    }
}
