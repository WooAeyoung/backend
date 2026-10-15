package kr.co.wooaeyoung.domain.standard;

import kr.co.wooaeyoung.domain.standard.StandardModels.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 질량 접두 단위와 성분별 환산(예: 비타민 D µg ↔ IU). */
public final class UnitConversion {
    private static final Map<String, Double> MASS = Map.of("UG", .001, "MG", 1d, "G", 1000d);

    private UnitConversion() {}

    public static boolean convertible(String source, String target, NutrientMaster nutrient) {
        return source.equals(target) || (MASS.containsKey(source) && MASS.containsKey(target)) || nutrient.unitConversions().containsKey(source);
    }

    /** 1 source 단위 = factor target 단위. 환산 근거가 없으면 null. */
    public static Double factor(String source, String target, NutrientMaster nutrient) {
        if (source.equals(target)) return 1.0;
        if (MASS.containsKey(source) && MASS.containsKey(target)) return MASS.get(source) / MASS.get(target);
        if (target.equals(nutrient.baseUnit()) && nutrient.unitConversions().containsKey(source)) return nutrient.unitConversions().get(source);
        if (source.equals(nutrient.baseUnit()) && nutrient.unitConversions().containsKey(target)) return 1 / nutrient.unitConversions().get(target);
        return null;
    }

    /** 공통 44개 + 해당 프로필 전용 성분. */
    public static Map<String, NutrientMaster> nutrientsFor(NutrientCatalog catalog, StandardProfileId profile) {
        Map<String, NutrientMaster> result = new LinkedHashMap<>();
        catalog.nutrients().forEach(nutrient -> result.put(nutrient.id(), nutrient));
        catalog.profileSpecific().stream().filter(nutrient -> nutrient.profiles().contains(profile)).forEach(nutrient -> result.put(nutrient.id(), nutrient));
        return result;
    }
}
