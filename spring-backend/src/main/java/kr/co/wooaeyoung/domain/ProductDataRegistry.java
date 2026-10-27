package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.standards.NutrientMaster;
import kr.co.wooaeyoung.units.Conversion;
import kr.co.wooaeyoung.units.Conversion.Status;
import kr.co.wooaeyoung.units.LabelBasis;
import kr.co.wooaeyoung.units.LabelValue;
import kr.co.wooaeyoung.units.NutrientForm;
import kr.co.wooaeyoung.units.UnitConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 제품 데이터 스키마 검증과 성분 정규화(BE-V3-011). CatalogService와 같은 products.json을 따로 읽는다.
 * 검증에 실패하면 계산 중 예외가 아니라 애플리케이션 시작 단계에서 실패한다. 스키마에 없는 필드는 실패시키지 않고 WARN 로그만 남긴다.
 * 정규화한 contents는 nutrients-master.json 기준 단위이고, 사료는 1 g당, 영양제는 1회 제공량당(라벨이 1 g당이면 1 g당)이다.
 */
@Component
public class ProductDataRegistry {
    public static final String DEFAULT_LOCATION = "products.json";
    /** 기준값표에는 없지만 라벨에서 받는 성분 ID(UnitConversionService.LABEL_ONLY). */
    public static final Set<String> LABEL_ONLY = UnitConversionService.LABEL_ONLY;

    public enum ContentBasis { PER_SERVING, PER_GRAM }

    /** 라벨 원문. nutrients의 같은 성분보다 우선한다(NIAS 분석). */
    public record Detail(Double amount, String unit, LabelBasis basis, NutrientForm form, LabelValue.Guarantee guarantee) {}
    public record DoseBand(Double maxKg, Double dose) {}
    public record ProductRecord(String id, String barcode, String name, String brand, String type,
                                Double servingAmount, String servingUnit, String dataQuality,
                                Map<String, Double> nutrients, Double recommendedDailyAmount, List<DoseBand> doseByWeight,
                                Boolean hasDetail, ContentBasis contentBasis, Map<String, Detail> nutrientDetails,
                                Double moisturePercent, Double energyKcalPerKg, List<String> tags, String dataSource, String updatedAt) {}
    record Root(List<ProductRecord> products) {}

    /** 제품 하나의 정규화 결과. contents의 값은 성분별 환산 기록이며, 환산하지 못한 성분은 value가 null이다. */
    public record ProductData(String id, String type, boolean hasDetail, ContentBasis contentBasis, double servingAmount, String servingUnit,
                              String dataQuality, List<String> tags, String dataSource, LocalDate updatedAt,
                              Map<String, Conversion> dogContents, Map<String, Conversion> catContents) {
        public Map<String, Conversion> contents(Species species) { return species == Species.CAT ? catContents : dogContents; }
    }

    private static final Logger log = LoggerFactory.getLogger(ProductDataRegistry.class);

    private final String location;
    private final List<String> warnings;
    private final List<ProductData> products;
    private final Map<String, ProductData> byId;
    private final UnitConversionService units;
    private final NutrientMaster master;

    public ProductDataRegistry(ObjectMapper mapper, UnitConversionService units, NutrientMaster master,
                               @Value("${wooaeyoung.catalog.products-resource:" + DEFAULT_LOCATION + "}") String location) {
        this.location = location;
        this.units = units;
        this.master = master;
        Root root;
        List<String> warnings = new ArrayList<>();
        try (InputStream input = new ClassPathResource(location).getInputStream()) {
            JsonNode tree = mapper.readTree(input);
            collectUnknownFields(tree, warnings);
            // 다른 팀원이 CatalogService용 필드를 추가해도 시작이 멈추지 않도록 알 수 없는 필드는 경고만 남긴다.
            root = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(tree, Root.class);
        } catch (IOException error) {
            throw new IllegalStateException("제품 데이터 파일을 읽을 수 없습니다: " + location + " (" + error.getMessage() + ")", error);
        }
        warnings.forEach(warning -> log.warn("제품 데이터 경고({}): {}", location, warning));
        this.warnings = List.copyOf(warnings);
        List<String> errors = new ArrayList<>();
        List<ProductData> loaded = new ArrayList<>();
        Map<String, ProductData> index = new LinkedHashMap<>();
        for (ProductRecord product : root.products() == null ? List.<ProductRecord>of() : root.products()) {
            List<String> productErrors = new ArrayList<>();
            ProductData data = load(product, productErrors);
            String label = product.id() == null ? "(id 없음)" : product.id();
            productErrors.forEach(error -> errors.add(label + ": " + error));
            if (data != null && index.put(data.id(), data) != null) errors.add(label + ": 중복 제품 ID");
            if (data != null) loaded.add(data);
        }
        if (!errors.isEmpty()) throw new IllegalStateException("제품 데이터 검증 실패(" + location + "): " + String.join("; ", errors));
        this.products = List.copyOf(loaded);
        this.byId = Collections.unmodifiableMap(index);
    }

    public String location() { return location; }
    /** 적재는 막지 않은 문제(알 수 없는 필드 등). 같은 내용이 WARN 로그로도 남는다. */
    public List<String> warnings() { return warnings; }

    /** 스키마에 없는 필드를 "제품 ID: 경로" 형태로 모은다. */
    private static void collectUnknownFields(JsonNode tree, List<String> warnings) {
        if (tree == null || !tree.isObject()) return;
        unknownFields(tree, Root.class, "", "(파일)", warnings);
        JsonNode products = tree.get("products");
        if (products == null || !products.isArray()) return;
        for (int i = 0; i < products.size(); i++) {
            JsonNode product = products.get(i);
            if (!product.isObject()) continue;
            String id = product.hasNonNull("id") ? product.get("id").asText() : "products[" + i + "]";
            unknownFields(product, ProductRecord.class, "", id, warnings);
            JsonNode details = product.get("nutrientDetails");
            if (details != null && details.isObject())
                details.fields().forEachRemaining(entry -> unknownFields(entry.getValue(), Detail.class, "nutrientDetails." + entry.getKey() + ".", id, warnings));
            JsonNode doses = product.get("doseByWeight");
            if (doses != null && doses.isArray())
                for (int j = 0; j < doses.size(); j++) unknownFields(doses.get(j), DoseBand.class, "doseByWeight[" + j + "].", id, warnings);
        }
    }

    private static void unknownFields(JsonNode node, Class<? extends Record> type, String prefix, String productId, List<String> warnings) {
        if (node == null || !node.isObject()) return;
        Set<String> known = new HashSet<>();
        for (RecordComponent component : type.getRecordComponents()) known.add(component.getName());
        node.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) warnings.add(productId + ": 알 수 없는 필드 " + prefix + name + " (무시함)");
        });
    }
    public List<ProductData> all() { return products; }
    public Optional<ProductData> find(String id) { return Optional.ofNullable(byId.get(id)); }

    private ProductData load(ProductRecord product, List<String> errors) {
        if (blank(product.id())) errors.add("id가 비어 있음");
        if (blank(product.name())) errors.add("name이 비어 있음");
        if (!"FEED".equals(product.type()) && !"SUPPLEMENT".equals(product.type())) errors.add("type은 FEED 또는 SUPPLEMENT여야 함: " + product.type());
        if (product.servingAmount() == null || !(product.servingAmount() > 0) || product.servingAmount().isInfinite()) errors.add("servingAmount는 0보다 커야 함: " + product.servingAmount());
        if (blank(product.servingUnit())) errors.add("servingUnit이 비어 있음");
        if (product.recommendedDailyAmount() != null && !(product.recommendedDailyAmount() > 0)) errors.add("recommendedDailyAmount는 0보다 커야 함");
        if (product.moisturePercent() != null && !(product.moisturePercent() >= 0 && product.moisturePercent() < 100)) errors.add("moisturePercent는 0 이상 100 미만: " + product.moisturePercent());
        if (product.energyKcalPerKg() != null && !(product.energyKcalPerKg() > 0)) errors.add("energyKcalPerKg는 0보다 커야 함: " + product.energyKcalPerKg());
        if (product.tags() != null && product.tags().stream().anyMatch(ProductDataRegistry::blank)) errors.add("tags에 빈 값이 있음");
        LocalDate updatedAt = null;
        if (product.updatedAt() != null) {
            try { updatedAt = LocalDate.parse(product.updatedAt()); }
            catch (DateTimeParseException error) { errors.add("updatedAt은 ISO 날짜(yyyy-MM-dd)여야 함: " + product.updatedAt()); }
        }
        validateDoses(product, errors);
        Map<String, Double> nutrients = product.nutrients() == null ? Map.of() : product.nutrients();
        Map<String, Detail> details = product.nutrientDetails() == null ? Map.of() : product.nutrientDetails();
        boolean detailed = !nutrients.isEmpty() || !details.isEmpty();
        boolean hasDetail = product.hasDetail() == null ? detailed : product.hasDetail();
        if (hasDetail != detailed) errors.add("hasDetail=" + hasDetail + "인데 성분 값은 " + (detailed ? "있음" : "없음"));
        ContentBasis basis = product.contentBasis() == null ? ContentBasis.PER_SERVING : product.contentBasis();
        boolean grams = "G".equalsIgnoreCase(product.servingUnit());
        if (basis == ContentBasis.PER_GRAM && !grams) errors.add("contentBasis=PER_GRAM은 servingUnit G에서만 쓸 수 있음");
        if ("FEED".equals(product.type()) && detailed && !grams) errors.add("사료 함량을 1 g당으로 정규화하려면 servingUnit이 G여야 함");
        if (!errors.isEmpty()) return null;

        Map<String, LabelValue> labels = new LinkedHashMap<>();
        nutrients.forEach((id, amount) -> {
            if (!known(id)) { errors.add("알 수 없는 성분 ID: " + id); return; }
            if (unitOf(id) == null) { errors.add(id + "는 단위가 정해지지 않아 nutrientDetails에 단위와 함께 적어야 함"); return; }
            if (amount == null || !(amount >= 0) || amount.isInfinite()) { errors.add("음수이거나 잘못된 함량: " + id + "=" + amount); return; }
            labels.put(id, LabelValue.of(amount, unitOf(id)));
        });
        details.forEach((id, detail) -> {
            if (!known(id)) { errors.add("알 수 없는 성분 ID: " + id); return; }
            if (detail == null || detail.amount() == null || !(detail.amount() >= 0) || detail.amount().isInfinite()) { errors.add("음수이거나 잘못된 함량: " + id); return; }
            if (blank(detail.unit())) { errors.add("단위가 없음: " + id); return; }
            labels.put(id, new LabelValue(detail.amount(), detail.unit(), detail.basis(), detail.form(), detail.guarantee()));
        });
        if (!errors.isEmpty()) return null;

        Map<Species, Map<String, Conversion>> contents = new EnumMap<>(Species.class);
        for (Species species : Species.values()) {
            UnitConversionService.Context context = new UnitConversionService.Context(species, product.moisturePercent(), product.energyKcalPerKg());
            Map<String, Conversion> converted = convertAll(product, labels, context, errors);
            Map<String, Conversion> normalized = new LinkedHashMap<>();
            converted.forEach((id, conversion) -> normalized.put(id, normalize(product, basis, conversion)));
            contents.put(species, Collections.unmodifiableMap(normalized));
            if (!errors.isEmpty()) break;   // 같은 오류를 종마다 반복해 적지 않는다
        }
        if (!errors.isEmpty()) return null;
        return new ProductData(product.id(), product.type(), hasDetail, basis, product.servingAmount(), product.servingUnit(),
                product.dataQuality(), product.tags() == null ? List.of() : List.copyOf(product.tags()), product.dataSource(), updatedAt,
                contents.get(Species.DOG), contents.get(Species.CAT));
    }

    /** 체중 구간은 maxKg 오름차순, 중복 없음, null(상한 없음)은 마지막 하나만. 구간은 (이전 maxKg, maxKg]라 정렬·중복 없음이면 겹치지 않는다. */
    private static void validateDoses(ProductRecord product, List<String> errors) {
        List<DoseBand> bands = product.doseByWeight();
        if (bands == null || bands.isEmpty()) return;
        if (!"SUPPLEMENT".equals(product.type())) errors.add("doseByWeight는 영양제에만 둘 수 있음");
        Double previous = null;
        for (int i = 0; i < bands.size(); i++) {
            DoseBand band = bands.get(i);
            if (band == null) { errors.add("doseByWeight[" + i + "]가 비어 있음"); continue; }
            if (band.dose() == null || !(band.dose() > 0)) errors.add("doseByWeight[" + i + "].dose는 0보다 커야 함");
            if (band.maxKg() == null) {
                if (i != bands.size() - 1) errors.add("doseByWeight[" + i + "]: 상한 없는 구간(maxKg null)은 마지막에만 둘 수 있음");
                continue;
            }
            if (!(band.maxKg() > 0)) errors.add("doseByWeight[" + i + "].maxKg는 0보다 커야 함");
            if (previous != null && !(band.maxKg() > previous)) errors.add("doseByWeight 구간이 정렬되지 않았거나 겹침: " + previous + " → " + band.maxKg());
            previous = band.maxKg();
        }
    }

    /**
     * 잘못된 단위(환산표에 없는 단위, 질량 성분에 IU 등)와 1 g당 표기인데 1회 제공량이 g가 아닌 경우는 적재 단계 오류다.
     * 원료·수분·열량 부족(INSUFFICIENT_DATA)은 데이터 상태로 보관한다.
     */
    private Map<String, Conversion> convertAll(ProductRecord product, Map<String, LabelValue> labels, UnitConversionService.Context context, List<String> errors) {
        Map<String, Conversion> result;
        try { result = units.convertLabels(labels, context); }
        catch (IllegalArgumentException error) { errors.add(error.getMessage()); return Map.of(); }
        result.forEach((id, conversion) -> {
            if (conversion.status() == Status.UNIT_CONVERSION_UNSUPPORTED)
                errors.add("잘못된 단위: " + id + " " + conversion.originalUnit() + " (" + conversion.note() + ")");
            if (conversion.perGram() && !"G".equalsIgnoreCase(product.servingUnit()))
                errors.add(id + ": 1 g당 표기(" + conversion.basis() + ")는 servingUnit이 G일 때만 쓸 수 있음");
        });
        return result;
    }

    /** 사료는 1 g당, 영양제는 1회 제공량당으로 맞춘다(계산명세서 0.4). */
    private static Conversion normalize(ProductRecord product, ContentBasis basis, Conversion conversion) {
        if (!conversion.converted() || conversion.perGram()) return conversion;
        if (basis == ContentBasis.PER_GRAM)
            return with(conversion, conversion.value(), conversion.formula() + "; 제품 데이터 1 g당 표기(contentBasis=PER_GRAM)");
        if (!"FEED".equals(product.type())) return conversion;
        double perGram = conversion.value() / product.servingAmount();
        return with(conversion, perGram, conversion.formula() + "; " + plain(conversion.value()) + " " + conversion.baseUnit()
                + " ÷ " + plain(product.servingAmount()) + " g(1회 제공량) = " + plain(perGram) + " " + conversion.baseUnit() + "/g");
    }

    private static Conversion with(Conversion c, double value, String formula) {
        return new Conversion(c.status(), value, true, c.originalValue(), c.originalUnit(), c.basis(), c.form(), c.baseUnit(), formula, c.source(), c.note());
    }

    private boolean known(String id) { return LABEL_ONLY.contains(id) || master.find(id).isPresent(); }

    /** nutrients 값의 단위: 기존 6개 성분은 CatalogService.NUTRIENT_UNITS(FEDIAF 응답 단위), 그 밖은 기준 단위. 라벨 전용 ID(EPA 등)는 null. */
    private String unitOf(String id) {
        String legacy = CatalogService.NUTRIENT_UNITS.get(id);
        return legacy != null ? legacy : master.find(id).map(NutrientMaster.Entry::baseUnit).orElse(null);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String plain(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}