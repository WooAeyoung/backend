package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.standards.NutrientLine;
import kr.co.wooaeyoung.standards.NutrientMeta;
import kr.co.wooaeyoung.units.Conversion;
import kr.co.wooaeyoung.units.Conversion.Status;
import kr.co.wooaeyoung.units.LabelValue;
import kr.co.wooaeyoung.units.UnitConversionService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * NIAS_V3 섭취량 합산(BE-V3-009, 처리기준 v3 7.1·계산명세서 6장). 성분마다 사료의 실제값 존재 여부를 따로 본다.
 * 1) 사료에 환산된 값이 있으면 실제값, 2) 라벨 값이 있으나 환산할 수 없으면 합계 null(INSUFFICIENT_DATA 등),
 * 3) 값이 없고 사료 항목이 있으며 완전사료이고 하한이 있으면 하한 × 사료 가정 배수(성분당 한 번),
 * 4) 그 외에는 0으로 더하되 정보 없음(unknownContribution)으로 표시한다. 사료 항목이 없으면 기존처럼 추정하지 않는다.
 */
@Component
public class NiasIntakeAggregator {
    public static final String ACTUAL = "ACTUAL", ESTIMATED = "ESTIMATED", UNKNOWN = "UNKNOWN";

    /** 성분 하나의 합산 결과. nullStatus가 있으면 합계를 만들지 않는다. */
    public record Intake(double feed, double supplement, String source, String nullStatus,
                         boolean unknownContribution, List<Map<String, Object>> provenance) {}
    public record Result(Map<String, Intake> totals, boolean estimated, List<String> warnings, List<Map<String, Object>> contributions) {}

    private record Item(String name, String type, boolean hasDetail, Map<String, Applied> nutrients) {}
    /** daily는 1일 섭취량(기준 단위). 환산하지 못했거나 값이 없으면 null. */
    private record Applied(Conversion conversion, Double daily) {}

    private final CatalogService catalog;
    private final ProductDataRegistry registry;
    private final UnitConversionService units;

    public NiasIntakeAggregator(CatalogService catalog, ProductDataRegistry registry, UnitConversionService units) {
        this.catalog = catalog;
        this.registry = registry;
        this.units = units;
    }

    public Result aggregate(AnalysisRequest request, Map<String, NutrientLine> lines, Map<String, NutrientMeta> meta, double feedFactor) {
        Species species = request.profile().species();
        List<Item> items = new ArrayList<>();
        for (FeedingItem item : request.safeItems()) items.add(catalogItem(item, species));
        for (ManualItem item : request.safeManualItems()) items.add(manualItem(item, species, meta));

        Set<String> ignored = new LinkedHashSet<>();
        boolean totalOmega3Only = false;
        for (Item item : items) for (Map.Entry<String, Applied> entry : item.nutrients().entrySet()) {
            if (!meta.containsKey(entry.getKey())) ignored.add(entry.getKey());
            Conversion conversion = entry.getValue().conversion();
            if (entry.getKey().equals("EPA_DHA") && conversion.status() == Status.NO_VALUE && conversion.note() != null && conversion.note().contains("총 오메가3"))
                totalOmega3Only = true;
        }

        boolean completeFeed = request.profile().usesCompleteFeed();
        List<Item> feeds = items.stream().filter(item -> item.type().equals("FEED")).toList();
        // 같은 내용의 항목이 둘 있을 수 있으므로 항목은 동일성으로 구분한다.
        Map<Item, Map<String, Double>> values = new IdentityHashMap<>();
        Map<Item, Map<String, String>> sources = new IdentityHashMap<>();
        items.forEach(item -> { values.put(item, new LinkedHashMap<>()); sources.put(item, new LinkedHashMap<>()); });
        Map<String, Intake> totals = new LinkedHashMap<>();
        boolean estimated = false;
        List<String> partiallyEstimated = new ArrayList<>(), unknown = new ArrayList<>();
        Set<Item> estimatedEmptyFeeds = Collections.newSetFromMap(new IdentityHashMap<>());

        for (String id : meta.keySet()) {
            double feed = 0, supplement = 0;
            String nullStatus = null, source = ACTUAL;
            boolean unknownContribution = false, feedActual = false;
            List<Map<String, Object>> provenance = new ArrayList<>();
            List<Item> missingFeeds = new ArrayList<>();
            for (Item item : feeds) {
                Applied applied = item.nutrients().get(id);
                if (applied != null && applied.conversion().converted()) {
                    feed += applied.daily(); feedActual = true;
                    record(provenance, values, sources, item, id, ACTUAL, applied.daily(), applied.conversion(), null);
                } else if (applied != null && !applied.conversion().treatedAsMissing()) {
                    nullStatus = worse(nullStatus, applied.conversion().status().name());
                    record(provenance, values, sources, item, id, applied.conversion().status().name(), null, applied.conversion(), null);
                } else missingFeeds.add(item);
            }
            if (!missingFeeds.isEmpty()) {
                Double minimum = lines.get(id).limit().minimum();
                if (!feedActual && nullStatus == null && completeFeed && minimum != null) {
                    double assumed = minimum * feedFactor;
                    feed += assumed; source = ESTIMATED; estimated = true;
                    Item first = missingFeeds.get(0);
                    record(provenance, values, sources, first, id, ESTIMATED, assumed, conversionOf(first, id),
                            "사료에 값이 없어 하한 × 사료 가정 배수(" + feedFactor + ")로 추정");
                    for (Item other : missingFeeds.subList(1, missingFeeds.size()))
                        record(provenance, values, sources, other, id, UNKNOWN, null, conversionOf(other, id), "추정은 성분당 한 번만 더함");
                    if (first.hasDetail()) partiallyEstimated.add(meta.get(id).name()); else estimatedEmptyFeeds.add(first);
                } else {
                    unknownContribution = true;
                    String note = feedActual ? "이 사료에는 값이 없어 다른 사료의 실제값만 합산" : completeFeed ? "사료에 값이 없고 하한이 없어 추정하지 않음" : "사료에 값이 없고 완전사료가 아니어서 0으로 계산(실제 0이 아님)";
                    for (Item item : missingFeeds) record(provenance, values, sources, item, id, UNKNOWN, null, conversionOf(item, id), note);
                }
            }
            for (Item item : items) {
                if (item.type().equals("FEED")) continue;
                Applied applied = item.nutrients().get(id);
                if (applied == null) continue;
                if (applied.conversion().converted()) {
                    supplement += applied.daily();
                    record(provenance, values, sources, item, id, ACTUAL, applied.daily(), applied.conversion(), null);
                } else if (applied.conversion().treatedAsMissing()) {
                    unknownContribution = true;
                    record(provenance, values, sources, item, id, UNKNOWN, null, applied.conversion(), applied.conversion().note());
                } else {
                    nullStatus = worse(nullStatus, applied.conversion().status().name());
                    record(provenance, values, sources, item, id, applied.conversion().status().name(), null, applied.conversion(), null);
                }
            }
            if (unknownContribution && nullStatus == null) unknown.add(meta.get(id).name());
            totals.put(id, new Intake(feed, supplement, source, nullStatus, unknownContribution, List.copyOf(provenance)));
        }

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> contributions = new ArrayList<>();
        for (Item item : items) {
            boolean empty = item.type().equals("FEED") && !item.hasDetail();
            if (empty && estimatedEmptyFeeds.contains(item)) warnings.add("사료 상세 성분이 없어 최소 권장량으로 추정했습니다.");
            else if (empty && !completeFeed) warnings.add("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.");
            Map<String, Object> contribution = new LinkedHashMap<>();
            contribution.put("name", item.name()); contribution.put("type", item.type());
            contribution.put("source", empty && estimatedEmptyFeeds.contains(item) ? ESTIMATED : ACTUAL);
            contribution.put("nutrients", values.get(item)); contribution.put("nutrientSources", sources.get(item));
            contributions.add(contribution);
        }
        if (!partiallyEstimated.isEmpty())
            warnings.add("사료 상세 성분에 없는 성분은 그 성분만 최소 권장량으로 추정했습니다: " + String.join(", ", partiallyEstimated));
        if (!unknown.isEmpty())
            warnings.add("정보 없음(UNKNOWN_CONTRIBUTION): " + String.join(", ", unknown) + "은(는) 일부 제품의 값이 없어 그 제품 몫을 0으로 계산했습니다. 실제 0이 아닙니다.");
        // 기존 경고 문구 유지(StandardProfileApiTest). 이 기준 프로필의 분석 대상이 아닌 제품 성분도 알린다.
        if (totalOmega3Only) warnings.add("총 오메가3 값은 EPA+DHA로 환산하지 않아 NIAS 분석에서 제외했습니다.");
        if (!ignored.isEmpty()) warnings.add("이 기준 프로필의 분석 대상이 아니어서 제외한 제품 성분: " + String.join(", ", ignored));
        return new Result(totals, estimated, warnings, contributions);
    }

    private Item catalogItem(FeedingItem item, Species species) {
        CatalogService.Product product = catalog.require(item.productId());
        if (!item.unit().equalsIgnoreCase(product.servingUnit())) throw new AnalysisException(product.name() + "의 급여 단위는 " + product.servingUnit() + "입니다.");
        ProductDataRegistry.ProductData data = registry.find(product.id())
                .orElseThrow(() -> new IllegalStateException("제품 데이터가 적재되지 않았습니다: " + product.id()));
        double ratio = item.dailyAmount() / product.servingAmount();
        Map<String, Applied> nutrients = new LinkedHashMap<>();
        data.contents(species).forEach((id, conversion) -> nutrients.put(id, applied(conversion, item.dailyAmount(), ratio)));
        return new Item(product.name(), product.type(), data.hasDetail(), nutrients);
    }

    private Item manualItem(ManualItem item, Species species, Map<String, NutrientMeta> meta) {
        Set<String> seen = new HashSet<>();
        Map<String, LabelValue> labels = new LinkedHashMap<>();
        for (ManualNutrient nutrient : item.nutrients()) {
            if (!seen.add(nutrient.nutrientId())) throw new AnalysisException("중복 성분입니다: " + nutrient.nutrientId());
            if (!meta.containsKey(nutrient.nutrientId()) && !UnitConversionService.LABEL_ONLY.contains(nutrient.nutrientId()))
                throw new AnalysisException("지원하지 않는 성분입니다: " + nutrient.nutrientId());
            labels.put(nutrient.nutrientId(), new LabelValue(nutrient.amount(), nutrient.unit(), nutrient.basis(), nutrient.form(), nutrient.guarantee()));
        }
        Map<String, Conversion> converted;
        try { converted = units.convertLabels(labels, new UnitConversionService.Context(species, item.moisturePercent(), item.energyKcalPerKg())); }
        catch (IllegalArgumentException error) { throw new AnalysisException(error.getMessage()); }
        double ratio = item.dailyAmount() / item.servingAmount();
        boolean grams = "G".equalsIgnoreCase(item.servingUnit());
        Map<String, Applied> nutrients = new LinkedHashMap<>();
        converted.forEach((id, conversion) -> {
            // 기존 동작: IU가 아닌 성분의 환산할 수 없는 단위는 400. IU 성분은 UNIT_CONVERSION_UNSUPPORTED 상태로 둔다.
            if (conversion.status() == Status.UNIT_CONVERSION_UNSUPPORTED && !"IU".equals(conversion.baseUnit()))
                throw new AnalysisException("변환할 수 없는 단위입니다: " + conversion.originalUnit() + " → " + conversion.baseUnit());
            if (conversion.converted() && conversion.perGram() && !grams)
                conversion = new Conversion(Status.INSUFFICIENT_DATA, null, true, conversion.originalValue(), conversion.originalUnit(), conversion.basis(),
                        conversion.form(), conversion.baseUnit(), conversion.formula(), conversion.source(), "1 g당 표기는 1회 제공량 단위(servingUnit)가 G일 때만 1일 섭취량으로 바꿀 수 있음");
            nutrients.put(id, applied(conversion, item.dailyAmount(), ratio));
        });
        return new Item(item.name(), item.type().name(), !item.nutrients().isEmpty(), nutrients);
    }

    /** 1 g당 값 × 1일 급여량(g) 또는 1회 제공량당 값 × (1일 급여량 ÷ 1회 제공량). */
    private static Applied applied(Conversion conversion, double dailyAmount, double ratio) {
        if (!conversion.converted()) return new Applied(conversion, null);
        return new Applied(conversion, conversion.perGram() ? conversion.value() * dailyAmount : conversion.value() * ratio);
    }

    private static Conversion conversionOf(Item item, String id) {
        Applied applied = item.nutrients().get(id);
        return applied == null ? null : applied.conversion();
    }

    private static void record(List<Map<String, Object>> provenance, Map<Item, Map<String, Double>> values, Map<Item, Map<String, String>> sources,
                               Item item, String id, String source, Double amount, Conversion conversion, String note) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", item.name()); entry.put("type", item.type()); entry.put("source", source); entry.put("amount", amount);
        entry.put("conversion", conversion == null ? null : conversion.toMap()); entry.put("note", note);
        provenance.add(entry);
        if (amount != null) values.get(item).put(id, amount);
        sources.get(item).put(id, source);
    }

    /** 합계를 만들 수 없는 사유가 여럿이면 INSUFFICIENT_DATA를 먼저 보인다. */
    private static String worse(String current, String next) {
        if (current == null) return next;
        return current.equals(Status.INSUFFICIENT_DATA.name()) || !next.equals(Status.INSUFFICIENT_DATA.name()) ? current : next;
    }
}
