package kr.co.wooaeyoung.standards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/** 영양소 마스터(classpath:standards/nutrients-master.json, NIAS 성분 ID 1~44). 프로필은 key로 참조한다. */
@Component
public class NutrientMaster {
    public static final String LOCATION = "standards/nutrients-master.json";
    public static final Set<String> ALLOWED_UNITS = Set.of("g", "mg", "µg", "IU");

    public record Entry(@NotNull @Positive Integer id, @NotBlank String key, @NotBlank String name,
                        @NotBlank String category, @NotBlank String baseUnit) {}
    public record File(@NotBlank String description, @NotBlank String sourceDocument, @NotEmpty List<@Valid @NotNull Entry> nutrients) {
        public File { nutrients = nutrients == null ? null : List.copyOf(nutrients); }
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byKey;

    @Autowired
    public NutrientMaster(StandardParametersLoader loader) {
        this(loader.load(LOCATION, File.class));
    }

    NutrientMaster(File file) {
        List<String> errors = new ArrayList<>();
        Map<String, Entry> keys = new LinkedHashMap<>();
        for (int i = 0; i < file.nutrients().size(); i++) {
            Entry entry = file.nutrients().get(i);
            if (entry.id() != i + 1) errors.add("ID가 1부터 연속이 아님: " + entry.key() + "=" + entry.id());
            if (keys.put(entry.key(), entry) != null) errors.add("중복 키: " + entry.key());
            if (!ALLOWED_UNITS.contains(entry.baseUnit())) errors.add("허용하지 않는 기준 단위: " + entry.key() + "=" + entry.baseUnit());
        }
        if (!errors.isEmpty()) throw new IllegalStateException(LOCATION + " 무결성 오류: " + String.join(", ", errors));
        this.entries = file.nutrients();
        this.byKey = Collections.unmodifiableMap(keys);
    }

    public List<Entry> entries() { return entries; }
    public Optional<Entry> find(String key) { return Optional.ofNullable(byKey.get(key)); }
}
