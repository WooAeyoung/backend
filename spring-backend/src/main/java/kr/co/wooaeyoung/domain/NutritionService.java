package kr.co.wooaeyoung.domain;

import kr.co.wooaeyoung.api.ApiModels.*;
import kr.co.wooaeyoung.ds.MaxHeap;
import kr.co.wooaeyoung.standards.Limit;
import kr.co.wooaeyoung.standards.NutrientLine;
import kr.co.wooaeyoung.standards.NutrientMeta;
import kr.co.wooaeyoung.standards.NutritionStandardStrategy;
import kr.co.wooaeyoung.standards.StandardProfile;
import kr.co.wooaeyoung.standards.StandardsProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class NutritionService {
    private static final Map<String, Double> UNIT = Map.of("UG", .001, "MG", 1d, "G", 1000d);
    private static final String IU = "IU";
    /** 합계를 기준 단위로 환산할 수 없음(IU 환산은 BE-V3-010). 합계는 0이 아니라 null이다. */
    static final String UNIT_CONVERSION_UNSUPPORTED = "UNIT_CONVERSION_UNSUPPORTED";
    /** 환산에 필요한 원료 형태·수분·열량이 없어 추정하지 않음(BE-V3-010, NIAS_V3). 합계는 null이다. */
    static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    static final String FEDIAF_IGNORED_LABEL_FIELDS = "FEDIAF_2025는 원료 형태·표기 기준·수분·열량 입력을 사용하지 않아 무시했습니다.";

    private final CatalogService catalog;
    private final StandardsProperties properties;
    private final Map<StandardProfile, NutritionStandardStrategy> strategies;
    private final NiasIntakeAggregator niasIntake;

    public NutritionService(CatalogService catalog, StandardsProperties properties, List<NutritionStandardStrategy> strategies, NiasIntakeAggregator niasIntake) {
        this.catalog = catalog;
        this.properties = properties;
        this.niasIntake = niasIntake;
        Map<StandardProfile, NutritionStandardStrategy> byProfile = new EnumMap<>(StandardProfile.class);
        for (NutritionStandardStrategy strategy : strategies)
            if (byProfile.put(strategy.profile(), strategy) != null) throw new IllegalStateException("기준 프로필 전략이 중복되었습니다: " + strategy.profile());
        for (StandardProfile profile : StandardProfile.values())
            if (!byProfile.containsKey(profile)) throw new IllegalStateException("기준 프로필 전략이 없습니다: " + profile);
        this.strategies = Map.copyOf(byProfile);
    }

    public Map<String, Object> analyze(AnalysisRequest request) {
        NutritionStandardStrategy strategy = strategy(request.standardProfileOrDefault());
        Map<String, NutrientMeta> meta = meta(strategy);
        validate(request, strategy);
        Profile profile = request.profile();
        String stage = strategy.lifeStage(profile);
        Double kcal = strategy.energy(profile, stage);
        Map<String, NutrientLine> lines = strategy.lines(profile, stage, kcal);
        boolean nias = strategy.profile() == StandardProfile.NIAS_V3;
        Aggregate aggregate = nias ? fromNias(niasIntake.aggregate(request, lines, meta, strategy.feedFactor()))
                : aggregate(request, lines, meta, strategy.feedFactor());
        List<Map<String, Object>> nutrients = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("deficient", 0); summary.put("adequate", 0); summary.put("caution", 0);
        summary.put("excess", 0); summary.put("noStandard", 0);
        // 판정에 쓰지 않은 성분은 기존 summary가 아니라 verificationSummary에 센다.
        Map<String, Integer> verificationSummary = new LinkedHashMap<>();
        for (String key : List.of("judgedWithVerified", "judgedWithUnverified", "noStandard", "unverifiedStandard", "unsupported", "unitConversionUnsupported"))
            verificationSummary.put(key, 0);
        if (nias) verificationSummary.put("insufficientData", 0);
        List<String> unconvertible = new ArrayList<>(), insufficient = new ArrayList<>();
        Map<String, Double> current = new LinkedHashMap<>();
        for (String id : meta.keySet()) {
            Total total = aggregate.totals().get(id);
            Double value = total.unconvertible ? null : total.feed + total.supplement;
            current.put(id, value);
            NutrientLine nutrientLine = lines.get(id);
            Limit line = nutrientLine.limit();
            String status;
            if (NutrientLine.UNSUPPORTED.equals(nutrientLine.blockedStatus())) status = NutrientLine.UNSUPPORTED;
            else if (value == null) status = total.nullStatus != null ? total.nullStatus : UNIT_CONVERSION_UNSUPPORTED;
            else if (nutrientLine.blockedStatus() != null) status = nutrientLine.blockedStatus();
            else status = classify(value, line);
            if (value == null) (INSUFFICIENT_DATA.equals(status) ? insufficient : unconvertible).add(meta.get(id).name());
            String verificationBucket = switch (status) {
                case NutrientLine.UNSUPPORTED -> "unsupported"; case UNIT_CONVERSION_UNSUPPORTED -> "unitConversionUnsupported";
                case INSUFFICIENT_DATA -> "insufficientData";
                case NutrientLine.UNVERIFIED_STANDARD -> "unverifiedStandard";
                default -> switch (nutrientLine.verificationStatus()) {
                    case VERIFIED -> "judgedWithVerified"; case PENDING_VERIFICATION -> "judgedWithUnverified"; case NOT_APPLICABLE -> "noStandard";
                };
            };
            verificationSummary.put(verificationBucket, verificationSummary.get(verificationBucket) + 1);
            if (nutrientLine.blockedStatus() == null && value != null) {
                String bucket = switch (status) {
                    case "DEFICIENT" -> "deficient"; case "CAUTION" -> "caution";
                    case "EXCESS" -> "excess"; case "NO_STANDARD" -> "noStandard"; default -> "adequate";
                };
                summary.put(bucket, summary.get(bucket) + 1);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nutrientId", id); row.put("name", meta.get(id).name()); row.put("unit", meta.get(id).unit());
            row.put("fromFeed", value == null ? null : total.feed); row.put("fromSupplements", value == null ? null : total.supplement); row.put("source", total.source);
            row.put("total", value); row.put("minimum", line.minimum()); row.put("caution", line.caution()); row.put("upper", line.upper()); row.put("status", status);
            row.put("nutrientNo", meta.get(id).nutrientNo()); row.put("verificationStatus", nutrientLine.verificationStatus()); row.put("reference", nutrientLine.reference());
            if (nias) { row.put("provenance", total.provenance); row.put("unknownContribution", total.unknownContribution); }
            nutrients.add(row);
        }
        Map<String, Object> ratios = new LinkedHashMap<>();
        boolean calciumPhosphorusAvailable = current.get("CALCIUM") != null && current.get("PHOSPHORUS") != null;
        String calciumPhosphorusBlocked = profile.species() == Species.DOG ? strategy.calciumPhosphorusBlockedStatus(stage) : null;
        // 기존 ratios는 고양이에서 빈 객체로 유지하고(하위 호환), 적용 여부는 새 필드로 알린다.
        String calciumPhosphorusApplicability = profile.species() != Species.DOG ? "NOT_APPLICABLE"
                : calciumPhosphorusBlocked != null ? calciumPhosphorusBlocked : calciumPhosphorusAvailable ? "APPLICABLE" : "UNAVAILABLE";
        if (profile.species() == Species.DOG && calciumPhosphorusBlocked == null && calciumPhosphorusAvailable) {
            double phosphorus = current.get("PHOSPHORUS");
            Double ratio = phosphorus == 0 ? null : current.get("CALCIUM") / phosphorus;
            ratios.put("calciumPhosphorus", Map.of("value", ratio == null ? 0d : ratio,
                    "status", strategy.calciumPhosphorusStatus(profile, current.get("CALCIUM"), phosphorus)));
        }
        List<String> warnings = new ArrayList<>(aggregate.warnings());
        warnings.addAll(strategy.analysisWarnings(profile, stage));
        int unverified = verificationSummary.get("unverifiedStandard");
        if (unverified > 0)
            warnings.add("기준 미검증: " + unverified + "개 성분의 기준값이 원문 대조 전(PENDING_VERIFICATION)이라 판정에 쓰지 않았습니다. 공식 기준이 아닙니다.");
        if (!unconvertible.isEmpty())
            warnings.add("단위 환산 미구현(BE-V3-010): " + String.join(", ", unconvertible) + "은(는) 제품 단위를 기준 단위로 환산할 수 없어 합계를 null로 둡니다.");
        if (!insufficient.isEmpty())
            warnings.add("정보 부족(INSUFFICIENT_DATA): " + String.join(", ", insufficient) + "은(는) 원료 형태·수분·열량 정보가 없어 추정하지 않고 합계를 null로 둡니다.");
        if (!nias && request.safeManualItems().stream().anyMatch(ManualItem::hasLabelDetail)) warnings.add(FEDIAF_IGNORED_LABEL_FIELDS);
        if (verificationSummary.get("judgedWithUnverified") > 0)
            warnings.add("테스트 설정(allow-unverified=true): 검증 대기 기준값으로 판정했습니다. 공식 기준이 아닙니다.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", fingerprint(request) + "-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("standardVersion", strategy.profile().version());
        result.put("standardSource", strategy.standardSource());
        result.put("cautionPolicy", strategy.cautionPolicy(profile.species()));
        result.put("lifeStage", stage); result.put("referenceEnergyKcal", kcal); result.put("usesEstimatedFeed", aggregate.estimated());
        result.put("summary", summary); result.put("nutrients", nutrients); result.put("contributions", aggregate.contributions());
        result.put("ratios", ratios); result.put("warnings", warnings);
        putProfileFields(result, strategy);
        result.put("ratioApplicability", Map.of("calciumPhosphorus", calciumPhosphorusApplicability));
        result.put("verificationSummary", verificationSummary);
        result.put("energyBasis", strategy.energyBasis(profile, stage, kcal));
        return result;
    }

    public Map<String, Object> recommend(RecommendationRequest request) {
        AnalysisRequest analysisRequest = request.analysisRequest();
        NutritionStandardStrategy strategy = strategy(analysisRequest.standardProfileOrDefault());
        Map<String, NutrientMeta> meta = meta(strategy);
        Map<String, Object> base = analyze(analysisRequest);
        String unsupported = strategy.recommendationUnsupportedReason();
        if (unsupported != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("traceId", base.get("traceId")); result.put("standardVersion", strategy.profile().version());
            result.put("message", "현재 기준 프로필에서는 추천 후보를 계산하지 않습니다.");
            result.put("items", List.of()); result.put("excluded", List.of()); result.put("usesEstimatedFeed", base.get("usesEstimatedFeed"));
            putProfileFields(result, strategy); result.put("warnings", List.of(unsupported));
            return result;
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> nutrientRows = (List<Map<String, Object>>) base.get("nutrients");
        Map<String, Double> current = new LinkedHashMap<>();
        Map<String, Limit> limits = new LinkedHashMap<>();
        Map<String, String> original = new LinkedHashMap<>();
        for (Map<String, Object> row : nutrientRows) {
            String id = row.get("nutrientId").toString();
            // 단위 환산 불가 성분(IU 등)은 분석 합계가 null일 수 있다.
            // 추천 후보 계산에서 0으로 오인해 부족 성분을 보완하려 하지 않도록
            // 기준값이 실제로 존재하는 성분만 점수 대상이 되게 한다.
            Double total = asDouble(row.get("total"));
            current.put(id, total == null ? 0d : total);
            limits.put(id, new Limit(asDouble(row.get("minimum")), asDouble(row.get("caution")), asDouble(row.get("upper"))));
            original.put(id, row.get("status").toString());
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Map<String, Object>> excluded = new LinkedHashMap<>();
        Set<String> alreadyFedSupplementNutrients = new HashSet<>();
        analysisRequest.safeItems().forEach(item -> {
            CatalogService.Product product = catalog.require(item.productId());
            if (product.type().equals("SUPPLEMENT")) alreadyFedSupplementNutrients.addAll(product.nutrients().keySet());
        });
        List<CatalogService.Product> remaining = new ArrayList<>(catalog.all().stream()
                .filter(product -> product.type().equals("SUPPLEMENT")
                        && (product.recommendedDailyAmount() != null || !product.doseByWeight().isEmpty())).toList());
        for (int round = 0; round < request.requestedMaxItems(strategy.defaultMaxItems()); round++) {
            Map<String, String> roundOriginal = original;
            MaxHeap<Candidate> candidates = new MaxHeap<>(Comparator.<Candidate>naturalOrder());
            for (CatalogService.Product product : remaining) {
                double dailyAmount = strategy.profile() == StandardProfile.NIAS_V3
                        ? product.doseForWeight(analysisRequest.profile().weightKg())
                        : product.recommendedDailyAmount();
                double servingRatio = dailyAmount / product.servingAmount();
                Map<String, Double> projected = new LinkedHashMap<>(current);
                product.nutrients().forEach((id, amount) -> projected.computeIfPresent(id, (key, value) -> value + amount * servingRatio));
                Map<String, String> statuses = new LinkedHashMap<>();
                projected.forEach((id, value) -> statuses.put(id, classify(value, limits.get(id))));
                List<String> harmful = product.nutrients().keySet().stream()
                        .filter(id -> "CAUTION".equals(statuses.get(id)) || "EXCESS".equals(statuses.get(id)))
                        .filter(meta::containsKey).map(id -> meta.get(id).name()).toList();
                List<String> reasons = new ArrayList<>(harmful);
                if (analysisRequest.profile().species() == Species.DOG &&
                        (!Collections.disjoint(product.nutrients().keySet(), Set.of("CALCIUM", "PHOSPHORUS")))) {
                    String ratioStatus = strategy.calciumPhosphorusStatus(analysisRequest.profile(), projected.get("CALCIUM"), projected.get("PHOSPHORUS"));
                    if ("LOW".equals(ratioStatus) || "HIGH".equals(ratioStatus)) reasons.add("칼슘:인 비율");
                }
                if (!reasons.isEmpty()) {
                    excluded.put(product.id(), Map.of("productId", product.id(), "name", product.name(), "reason", "추가 후 기준 이탈 예상: " + String.join(", ", reasons)));
                    continue;
                }
                long fixed = roundOriginal.entrySet().stream()
                        .filter(entry -> "DEFICIENT".equals(entry.getValue()) && !"DEFICIENT".equals(statuses.get(entry.getKey()))).count();
                long overlap = strategy.profile() == StandardProfile.NIAS_V3
                        ? product.nutrients().keySet().stream().filter(alreadyFedSupplementNutrients::contains).count()
                        : product.nutrients().keySet().stream()
                                .filter(id -> roundOriginal.get(id) != null
                                        && !"DEFICIENT".equals(roundOriginal.get(id))
                                        && !"NO_STANDARD".equals(roundOriginal.get(id))).count();
                double margin = safetyMargin(product, projected, limits, servingRatio);
                double score = strategy.recommendationScore(fixed, overlap);
                Candidate candidate = new Candidate(score, fixed, margin, overlap, product, dailyAmount, projected, statuses);
                candidates.push(candidate);
            }
            Candidate best = candidates.pop();
            if (best == null) break;
            CatalogService.Product product = best.product;
            Map<String, Object> selectedItem = new LinkedHashMap<>();
            selectedItem.put("productId", product.id()); selectedItem.put("name", product.name());
            selectedItem.put("dailyAmount", best.dailyAmount); selectedItem.put("unit", product.servingUnit());
            selectedItem.put("score", best.score); selectedItem.put("fixedNutrients", best.fixed);
            if (strategy.profile() == StandardProfile.NIAS_V3) {
                selectedItem.put("margin", best.margin); selectedItem.put("overlap", best.overlap);
                selectedItem.put("scoreBreakdown", Map.of("fixedNutrients", best.fixed, "margin", best.margin, "overlap", best.overlap));
            }
            selectedItem.put("projectedStatuses", best.statuses);
            selected.add(selectedItem);
            current = best.projected; original = best.statuses;
            remaining.removeIf(item -> item.id().equals(product.id()));
        }
        @SuppressWarnings("unchecked") Map<String, Integer> summary = (Map<String, Integer>) base.get("summary");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", base.get("traceId")); result.put("standardVersion", strategy.profile().version());
        result.put("message", summary.get("deficient") == 0 ? "적용 가능한 기준에서 주의·과다가 검출되지 않은 후보입니다." : "부족 성분, 기준선까지의 여유, 중복 성분을 함께 고려했습니다.");
        result.put("items", selected); result.put("excluded", new ArrayList<>(excluded.values())); result.put("usesEstimatedFeed", base.get("usesEstimatedFeed"));
        putProfileFields(result, strategy); result.put("warnings", List.of());
        return result;
    }

    public List<Map<String, String>> nutrients() {
        return strategy(StandardProfile.DEFAULT).nutrients().stream().map(n -> Map.of("id", n.id(), "name", n.name(), "unit", n.unit())).toList();
    }

    /** 프로필의 전체 영양소 목록(NIAS 44개, FEDIAF 6개)과 기준값·출처·검증 상태. */
    public Map<String, Object> nutrientCatalog(StandardProfile profile) {
        NutritionStandardStrategy strategy = strategy(profile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("standardVersion", strategy.profile().version());
        putProfileFields(result, strategy);
        result.put("items", strategy.nutrientCatalog());
        return result;
    }

    public Map<String, Object> parameterCatalog(StandardProfile profile) {
        NutritionStandardStrategy selected = strategy(profile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("standardProfile", selected.profile().name());
        result.put("standardVersion", selected.profile().version());
        result.put("parameterVersion", selected.parameterVersion());
        result.put("verification", selected.parameterVerification());
        result.put("allowUnverified", properties.allowUnverified());
        return result;
    }

    private NutritionStandardStrategy strategy(StandardProfile profile) { return strategies.get(profile); }

    private static Map<String, NutrientMeta> meta(NutritionStandardStrategy strategy) {
        Map<String, NutrientMeta> meta = new LinkedHashMap<>();
        strategy.nutrients().forEach(nutrient -> meta.put(nutrient.id(), nutrient));
        return meta;
    }

    private void putProfileFields(Map<String, Object> result, NutritionStandardStrategy strategy) {
        result.put("standardProfile", strategy.profile().name());
        result.put("parameterVersion", strategy.parameterVersion());
        result.put("parameterVerification", strategy.parameterVerification());
        result.put("standardsVerification", Map.of("allowUnverified", properties.allowUnverified()));
    }

    private static void validate(AnalysisRequest request, NutritionStandardStrategy strategy) {
        int count = request.safeItems().size() + request.safeManualItems().size();
        if (count == 0) throw new AnalysisException("분석할 사료 또는 영양제를 하나 이상 추가해주세요.");
        if (count > 20) throw new AnalysisException("제품은 최대 20개까지 분석할 수 있습니다.");
        strategy.validate(request.profile());
    }

    private Aggregate aggregate(AnalysisRequest request, Map<String, NutrientLine> lines, Map<String, NutrientMeta> meta, double feedFactor) {
        Map<String, Total> totals = new LinkedHashMap<>(); meta.keySet().forEach(id -> totals.put(id, new Total()));
        boolean estimated = false; List<String> warnings = new ArrayList<>(); List<Map<String, Object>> contributions = new ArrayList<>();
        Set<String> ignored = new LinkedHashSet<>();
        for (FeedingItem item : request.safeItems()) {
            CatalogService.Product product = catalog.require(item.productId());
            if (!item.unit().equalsIgnoreCase(product.servingUnit())) throw new AnalysisException(product.name() + "의 급여 단위는 " + product.servingUnit() + "입니다.");
            double ratio = item.dailyAmount() / product.servingAmount(); Map<String, Double> values = new LinkedHashMap<>();
            if (product.type().equals("FEED") && product.nutrients().isEmpty() && request.profile().usesCompleteFeed()) {
                estimated = true;
                for (String id : meta.keySet()) { Double minimum = lines.get(id).limit().minimum(); if (minimum != null) { double assumed = minimum * feedFactor; totals.get(id).feed += assumed; totals.get(id).source = "ESTIMATED"; values.put(id, assumed); } }
                warnings.add("사료 상세 성분이 없어 최소 권장량으로 추정했습니다.");
            } else {
                product.nutrients().forEach((id, amount) -> {
                    if (!totals.containsKey(id)) { ignored.add(id); return; }
                    String from = CatalogService.NUTRIENT_UNITS.get(id), to = meta.get(id).unit();
                    double applied;
                    if (to.equals(from)) applied = amount * ratio;
                    else if (from != null && UNIT.containsKey(from) && UNIT.containsKey(to)) applied = convert(amount, from, to) * ratio;
                    else { totals.get(id).unconvertible = true; return; }
                    totals.get(id).add(product.type(), applied); values.put(id, applied);
                });
                if (product.type().equals("FEED") && product.nutrients().isEmpty()) warnings.add("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.");
            }
            contributions.add(contribution(product.name(), product.type(), estimated && product.nutrients().isEmpty() ? "ESTIMATED" : "ACTUAL", values));
        }
        for (ManualItem item : request.safeManualItems()) {
            double ratio = item.dailyAmount() / item.servingAmount(); Set<String> seen = new HashSet<>(); Map<String, Double> values = new LinkedHashMap<>();
            for (ManualNutrient nutrient : item.nutrients()) {
                if (!seen.add(nutrient.nutrientId())) throw new AnalysisException("중복 성분입니다: " + nutrient.nutrientId());
                if (!meta.containsKey(nutrient.nutrientId())) throw new AnalysisException("지원하지 않는 성분입니다: " + nutrient.nutrientId());
                String target = meta.get(nutrient.nutrientId()).unit();
                double applied;
                if (target.equals(IU)) {
                    // IU 성분은 IU로 입력된 값만 합산한다. 질량 단위 → IU 환산은 BE-V3-010 범위.
                    if (!nutrient.unit().equalsIgnoreCase(IU)) { totals.get(nutrient.nutrientId()).unconvertible = true; continue; }
                    applied = nutrient.amount() * ratio;
                } else applied = convert(nutrient.amount(), nutrient.unit(), target) * ratio;
                totals.get(nutrient.nutrientId()).add(item.type().name(), applied); values.put(nutrient.nutrientId(), applied);
            }
            contributions.add(contribution(item.name(), item.type().name(), "ACTUAL", values));
        }
        // 이 기준 프로필의 분석 대상이 아닌 제품 성분은 합산하지 않지만, 섭취하지 않은 것으로 오해하지 않도록 알린다.
        // FEDIAF는 제품 성분 6개가 모두 분석 대상이라 해당 없음(OMEGA3 무시는 NIAS에서만 생긴다).
        if (ignored.remove("OMEGA3")) warnings.add("총 오메가3 값은 EPA+DHA로 환산하지 않아 NIAS 분석에서 제외했습니다.");
        if (!ignored.isEmpty()) warnings.add("이 기준 프로필의 분석 대상이 아니어서 제외한 제품 성분: " + String.join(", ", ignored));
        return new Aggregate(totals, estimated, warnings, contributions);
    }

    private static Map<String, Object> contribution(String name, String type, String source, Map<String, Double> nutrients) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("name", name); value.put("type", type); value.put("source", source); value.put("nutrients", nutrients); return value;
    }
    private static double convert(double value, String source, String target) {
        Double from = UNIT.get(source.toUpperCase(Locale.ROOT)), to = UNIT.get(target.toUpperCase(Locale.ROOT));
        if (from == null || to == null) throw new AnalysisException("변환할 수 없는 단위입니다: " + source + " → " + target);
        return value * from / to;
    }
    /** 계산명세서 7장 judge. 하한·주의선·상한이 모두 null일 때만 기준 없음이다(주의선만 있는 성분도 판정한다). */
    static String classify(double total, Limit line) {
        if (line.minimum() == null && line.upper() == null && line.caution() == null) return "NO_STANDARD";
        if (line.minimum() != null && total < line.minimum()) return "DEFICIENT";
        if (line.upper() != null && total > line.upper()) return "EXCESS";
        if (line.caution() != null && total >= line.caution()) return "CAUTION";
        return line.upper() != null ? "ADEQUATE" : "ADEQUATE_NO_UPPER_LIMIT";
    }
    private static String fingerprint(Object request) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest, 0, 8); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Double asDouble(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
    private static double safetyMargin(CatalogService.Product product, Map<String, Double> projected,
                                       Map<String, Limit> limits, double servingRatio) {
        double minimum = 1d;
        boolean found = false;
        for (String id : product.nutrients().keySet()) {
            Limit limit = limits.get(id);
            if (limit == null || limit.caution() == null || limit.minimum() == null || limit.caution() <= limit.minimum()) continue;
            double trial = projected.getOrDefault(id, 0d);
            minimum = Math.min(minimum, (limit.caution() - trial) / (limit.caution() - limit.minimum()));
            found = true;
        }
        return found ? minimum : 1d;
    }
    private static final class Total { double feed; double supplement; boolean unconvertible; String source = "ACTUAL";
        // NIAS_V3 전용(BE-V3-009): 합계를 만들지 못한 사유, 정보 없음 기여 여부, 제품별 근거.
        String nullStatus; boolean unknownContribution; List<Map<String, Object>> provenance;
        void add(String type, double value) { if (type.equals("FEED")) feed += value; else supplement += value; } }
    /** NIAS 성분별 합산 결과를 기존 Aggregate 형태로 옮긴다. 합계를 만들지 못한 성분은 unconvertible로 두어 total이 null이 된다. */
    private static Aggregate fromNias(NiasIntakeAggregator.Result result) {
        Map<String, Total> totals = new LinkedHashMap<>();
        result.totals().forEach((id, intake) -> {
            Total total = new Total();
            total.feed = intake.feed(); total.supplement = intake.supplement(); total.source = intake.source();
            total.unconvertible = intake.nullStatus() != null; total.nullStatus = intake.nullStatus();
            total.unknownContribution = intake.unknownContribution(); total.provenance = intake.provenance();
            totals.put(id, total);
        });
        return new Aggregate(totals, result.estimated(), result.warnings(), result.contributions());
    }
    private record Aggregate(Map<String, Total> totals, boolean estimated, List<String> warnings, List<Map<String, Object>> contributions) {}
    private record Candidate(double score, long fixed, double margin, long overlap, CatalogService.Product product,
                             double dailyAmount, Map<String, Double> projected, Map<String, String> statuses) implements Comparable<Candidate> {
        public int compareTo(Candidate other) { int scoreResult = Double.compare(score, other.score); return scoreResult != 0 ? scoreResult : other.product.name().compareTo(product.name()); }
    }
}
