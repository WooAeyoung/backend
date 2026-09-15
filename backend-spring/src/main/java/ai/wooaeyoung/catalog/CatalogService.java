package ai.wooaeyoung.catalog;

import ai.wooaeyoung.domain.NutrientAmount;
import ai.wooaeyoung.domain.NutrientStandard;
import ai.wooaeyoung.domain.Product;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 제품 카탈로그·기준표 (F-023/024/034). 원본은 SQLite CRUD였지만, 데모 데이터가
 * data/processed/*.csv 로 고정돼 있어 이 포트에서는 시작 시 CSV를 읽어 메모리에 올린다.
 */
@Service
public class CatalogService {

    private List<Product> products = List.of();
    private List<NutrientStandard> standards = List.of();

    @PostConstruct
    void load() throws Exception {
        products = loadProducts();
        standards = loadStandards();
    }

    public List<Product> products() {
        return products;
    }

    public List<NutrientStandard> standards() {
        return standards;
    }

    public Product findProduct(String productId) {
        return products.stream().filter(p -> p.productId().equals(productId)).findFirst().orElse(null);
    }

    private List<Product> loadProducts() throws Exception {
        Map<String, List<NutrientAmount>> nutrientsByProduct = new LinkedHashMap<>();
        Map<String, String[]> header = new LinkedHashMap<>(); // productId -> [name, brand, category, servingBasisG, monthlyPrice, source]

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/products.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            List<String> cols = CsvUtil.parseLine(headerLine);
            int idxId = cols.indexOf("product_id");
            int idxName = cols.indexOf("product_name");
            int idxBrand = cols.indexOf("brand");
            int idxCategory = cols.indexOf("category");
            int idxServing = cols.indexOf("serving_basis_g");
            int idxNutrient = cols.indexOf("nutrient");
            int idxAmount = cols.indexOf("amount_mg");
            int idxComplete = cols.indexOf("label_complete");
            int idxPrice = cols.indexOf("monthly_price_krw");
            int idxSource = cols.indexOf("source");

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = CsvUtil.parseLine(line);
                String productId = row.get(idxId);
                header.putIfAbsent(productId, new String[]{
                        row.get(idxName), row.get(idxBrand), row.get(idxCategory),
                        row.get(idxServing), row.get(idxPrice), row.get(idxSource)
                });
                nutrientsByProduct
                        .computeIfAbsent(productId, k -> new ArrayList<>())
                        .add(new NutrientAmount(
                                row.get(idxNutrient),
                                Double.parseDouble(row.get(idxAmount)),
                                Boolean.parseBoolean(row.get(idxComplete))
                        ));
            }
        }

        List<Product> out = new ArrayList<>();
        for (var e : header.entrySet()) {
            String[] h = e.getValue();
            out.add(new Product(
                    e.getKey(),
                    h[0],
                    h[1],
                    h[2],
                    Double.parseDouble(h[3]),
                    h[4].isBlank() ? 0 : (int) Double.parseDouble(h[4]),
                    h[5],
                    nutrientsByProduct.get(e.getKey())
            ));
        }
        return out;
    }

    private List<NutrientStandard> loadStandards() throws Exception {
        List<NutrientStandard> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/nutrient_standards.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            List<String> cols = CsvUtil.parseLine(headerLine);
            int idxN = cols.indexOf("nutrient");
            int idxMin = cols.indexOf("demo_min_mg");
            int idxMax = cols.indexOf("demo_max_mg");
            int idxUnit = cols.indexOf("unit");
            int idxSource = cols.indexOf("source");
            int idxUrl = cols.indexOf("source_url");
            int idxVersion = cols.indexOf("version");
            int idxBasis = cols.indexOf("basis");
            int idxVerified = cols.indexOf("verified");

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = CsvUtil.parseLine(line);
                out.add(new NutrientStandard(
                        row.get(idxN),
                        Double.parseDouble(row.get(idxMin)),
                        Double.parseDouble(row.get(idxMax)),
                        row.get(idxUnit),
                        row.get(idxSource),
                        row.get(idxUrl),
                        row.get(idxVersion),
                        row.get(idxBasis),
                        Boolean.parseBoolean(row.get(idxVerified))
                ));
            }
        }
        return out;
    }
}
