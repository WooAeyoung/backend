package kr.co.wooaeyoung.units;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.wooaeyoung.api.ApiModels.Species;
import kr.co.wooaeyoung.standards.StandardParametersLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 단위 환산 계수표(classpath:units/conversion-factors.json). 환산 계수는 이 파일에만 둔다.
 * 표가 불완전하면(원료 형태·표기 형태 누락, 중복) 애플리케이션 시작이 실패한다.
 */
@Component
public class UnitConversionFactors {
    public static final String LOCATION = "units/conversion-factors.json";

    public record MassUnit(@NotBlank String unit, @NotNull Integer gramsExponent, @NotBlank String source) {}
    public record Alias(@NotBlank String alias, @NotBlank String unit, @NotBlank String source) {}
    public record BasisEntry(@NotNull LabelBasis basis, @NotNull @Positive Double divisor, @NotNull Boolean perGram,
                             @Pattern(regexp = "ENERGY|MOISTURE") String requires, @NotBlank String source) {}
    /** amount(unit) = iu IU. excluded면 그 종에서는 환산하지 않는다. species가 null이면 모든 종. */
    public record IuFactor(@NotBlank String nutrient, @NotNull NutrientForm form, @NotNull @Positive Double amount,
                           @NotBlank String unit, @NotNull @PositiveOrZero Double iu, Species species,
                           @NotNull Boolean excluded, @NotNull Boolean assumed, String note, @NotBlank String source) {}
    public record Omega3(@NotBlank String target, @NotNull @Size(min = 2, max = 2) List<@NotBlank String> components,
                         @NotBlank String totalOnly, @NotBlank String source) {}
    public record File(@NotBlank String version, @NotBlank String sourceDocument,
                       @NotEmpty List<@Valid @NotNull MassUnit> massUnits, @NotEmpty List<@Valid @NotNull Alias> unitAliases,
                       @NotEmpty List<@Valid @NotNull BasisEntry> labelBases, @NotEmpty List<@Valid @NotNull IuFactor> iuFactors,
                       @Valid @NotNull Omega3 omega3) {}

    private final File file;
    private final Map<String, MassUnit> massUnits = new LinkedHashMap<>();
    private final Map<String, Alias> aliases = new LinkedHashMap<>();
    private final Map<LabelBasis, BasisEntry> bases = new EnumMap<>(LabelBasis.class);
    private final Map<NutrientForm, List<IuFactor>> iuFactors = new EnumMap<>(NutrientForm.class);

    @Autowired
    public UnitConversionFactors(StandardParametersLoader loader) {
        this(loader.load(LOCATION, File.class));
    }

    UnitConversionFactors(File file) {
        this.file = file;
        List<String> errors = new ArrayList<>();
        for (MassUnit unit : file.massUnits())
            if (massUnits.put(unit.unit(), unit) != null) errors.add("중복 질량 단위: " + unit.unit());
        Set<String> known = new HashSet<>(massUnits.keySet());
        known.addAll(List.of("IU", "%", "ppm"));
        for (Alias alias : file.unitAliases()) {
            String key = alias.alias().toLowerCase(Locale.ROOT);
            if (aliases.put(key, alias) != null) errors.add("중복 단위 별칭: " + alias.alias());
            if (!known.contains(alias.unit())) errors.add("알 수 없는 단위로의 별칭: " + alias.alias() + " → " + alias.unit());
        }
        for (BasisEntry entry : file.labelBases())
            if (bases.put(entry.basis(), entry) != null) errors.add("중복 표기 형태: " + entry.basis());
        for (LabelBasis basis : LabelBasis.values())
            if (!bases.containsKey(basis)) errors.add("표기 형태 계수 없음: " + basis);
        for (IuFactor factor : file.iuFactors()) {
            if (!factor.form().nutrient().equals(factor.nutrient())) errors.add("원료와 성분 불일치: " + factor.form() + " → " + factor.nutrient());
            if (!massUnits.containsKey(factor.unit())) errors.add("IU 계수의 단위가 질량 단위가 아님: " + factor.form() + "=" + factor.unit());
            if (!factor.excluded() && factor.iu() <= 0) errors.add("IU 계수가 0 이하: " + factor.form());
            if (factor.assumed() && (factor.note() == null || factor.note().isBlank())) errors.add("간주 계수에 note 없음: " + factor.form());
            List<IuFactor> list = iuFactors.computeIfAbsent(factor.form(), key -> new ArrayList<>());
            if (list.stream().anyMatch(other -> Objects.equals(other.species(), factor.species()) || other.species() == null || factor.species() == null))
                errors.add("원료 계수 중복(종 구분 겹침): " + factor.form());
            list.add(factor);
        }
        for (NutrientForm form : NutrientForm.values())
            if (!iuFactors.containsKey(form)) errors.add("원료 계수 없음: " + form);
        if (!errors.isEmpty()) throw new IllegalStateException(LOCATION + " 무결성 오류: " + String.join(", ", errors));
    }

    public String version() { return file.version(); }
    public Omega3 omega3() { return file.omega3(); }
    public Optional<MassUnit> massUnit(String unit) { return Optional.ofNullable(massUnits.get(unit)); }
    public BasisEntry basis(LabelBasis basis) { return bases.get(basis); }

    /** 라벨 단위 표기를 정규화한다(μg·mcg·ug → µg, iu → IU). 표에 없으면 empty. */
    public Optional<Alias> alias(String unit) {
        return unit == null ? Optional.empty() : Optional.ofNullable(aliases.get(unit.strip().toLowerCase(Locale.ROOT)));
    }

    /** 종에 맞는 IU 계수. 종 구분이 있는 원료인데 종이 없으면 empty. */
    public Optional<IuFactor> iuFactor(NutrientForm form, Species species) {
        return iuFactors.getOrDefault(form, List.of()).stream()
                .filter(factor -> factor.species() == null || factor.species() == species).findFirst();
    }
}