package kr.co.wooaeyoung.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NIAS 추천 후보의 하루 급여량과 적용한 체중 구간(BE-V3-012, 계산명세서 10.2 "weight_kg 이하 첫 구간").
 * 급여량 값은 CatalogService.Product.doseForWeight가 정한다. 여기서는 같은 규칙으로 어느 구간이 적용됐는지만 찾는다.
 * 구간은 (이전 maxKg, maxKg]이고 maxKg null은 상한 없는 마지막 구간이다(ProductDataRegistry가 시작 시 정렬·중복을 검사한다).
 *
 * @param source          DOSE_BY_WEIGHT 또는 RECOMMENDED_DAILY_AMOUNT(doseByWeight가 없는 제품)
 * @param bandIndex       적용한 doseByWeight 구간 번호. 구간이 없으면 null
 * @param aboveKg         구간 하한(초과). 첫 구간이면 null
 * @param maxKg           구간 상한(이하). 상한 없는 구간이면 null
 * @param beyondLastBand  체중이 모든 구간의 상한을 넘어 마지막 구간을 그대로 쓴 경우
 */
public record NiasDose(double dose, String source, Integer bandIndex, Double aboveKg, Double maxKg, boolean beyondLastBand) {
    public static final String DOSE_BY_WEIGHT = "DOSE_BY_WEIGHT", RECOMMENDED_DAILY_AMOUNT = "RECOMMENDED_DAILY_AMOUNT";

    public static NiasDose of(CatalogService.Product product, double weightKg) {
        double dose = product.doseForWeight(weightKg);
        List<CatalogService.DoseBand> bands = product.doseByWeight();
        if (bands == null || bands.isEmpty()) return new NiasDose(dose, RECOMMENDED_DAILY_AMOUNT, null, null, null, false);
        for (int i = 0; i < bands.size(); i++) {
            CatalogService.DoseBand band = bands.get(i);
            if (band.maxKg() == null || weightKg <= band.maxKg())
                return new NiasDose(dose, DOSE_BY_WEIGHT, i, i == 0 ? null : bands.get(i - 1).maxKg(), band.maxKg(), false);
        }
        int last = bands.size() - 1;
        return new NiasDose(dose, DOSE_BY_WEIGHT, last, last == 0 ? null : bands.get(last - 1).maxKg(), bands.get(last).maxKg(), true);
    }

    /** 추천 응답 doseBand. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source); result.put("bandIndex", bandIndex); result.put("aboveKg", aboveKg);
        result.put("maxKg", maxKg); result.put("dose", dose); result.put("beyondLastBand", beyondLastBand);
        return result;
    }
}
