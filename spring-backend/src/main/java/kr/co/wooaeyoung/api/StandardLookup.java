package kr.co.wooaeyoung.api;

import com.fasterxml.jackson.databind.JsonNode;
import kr.co.wooaeyoung.api.ApiResponses.*;
import kr.co.wooaeyoung.standards.StandardParametersLoader;
import kr.co.wooaeyoung.standards.StandardProfile;
import kr.co.wooaeyoung.units.UnitConversionFactors;
import kr.co.wooaeyoung.units.UnitConversionService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 조회 API(BE-V3-016)가 더하는 값. 모두 저장소의 데이터 파일에서 읽은 그대로이고 새 값을 만들지 않는다.
 * <ul>
 *   <li>parameters: standards/{profile}/parameters.json 원문(키 순서 그대로). 매개변수 record는 Map.copyOf로 순서가 바뀌므로 원문 트리를 쓴다.</li>
 *   <li>labelInput: units/conversion-factors.json의 입력 단위·표기 기준·원료 형태. NIAS_V3만 이 표로 환산하므로 FEDIAF_2025는 null이다.</li>
 * </ul>
 */
@Component
public class StandardLookup {
    private final Map<StandardProfile, JsonNode> parameterFiles = new EnumMap<>(StandardProfile.class);
    private final LabelInput labelInput;

    public StandardLookup(StandardParametersLoader loader) {
        for (StandardProfile profile : StandardProfile.values()) parameterFiles.put(profile, loader.load(profile, JsonNode.class));
        UnitConversionFactors.File units = loader.load(UnitConversionFactors.LOCATION, UnitConversionFactors.File.class);
        Map<String, List<FormOption>> forms = new LinkedHashMap<>();
        for (UnitConversionFactors.IuFactor factor : units.iuFactors())
            forms.computeIfAbsent(factor.nutrient(), key -> new ArrayList<>())
                    .add(new FormOption(factor.form(), factor.species(), factor.excluded(), factor.assumed()));
        forms.replaceAll((nutrient, options) -> List.copyOf(options));
        this.labelInput = new LabelInput(units.version(), units.sourceDocument(),
                units.unitAliases().stream().map(alias -> new UnitOption(alias.alias(), alias.unit())).toList(),
                units.labelBases().stream().map(basis -> new BasisOption(basis.basis(), basis.perGram(), basis.requires())).toList(),
                Collections.unmodifiableMap(forms),
                UnitConversionService.LABEL_ONLY.stream().sorted().toList());
    }

    public JsonNode parameters(StandardProfile profile) { return parameterFiles.get(profile).deepCopy(); }

    public LabelInput labelInput(StandardProfile profile) { return profile == StandardProfile.NIAS_V3 ? labelInput : null; }
}
