package kr.co.wooaeyoung.domain.standard;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.domain.standard.StandardModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 기준 프로필 레지스트리. 시작 시 한 번 적재하고 이후에는 읽기만 한다. */
@Service
public class StandardRegistry {
    private final NutrientCatalog catalog;
    private final Map<StandardProfileId, StandardProfile> profiles;
    private final Map<StandardProfileId, Map<String, NutrientMaster>> nutrients;
    // (성분/기준표 생애주기) → 레코드 해시 색인
    private final Map<StandardProfileId, Map<String, StandardRecord>> recordIndex;
    private final boolean allowUnverified;

    @Autowired
    public StandardRegistry(ObjectMapper mapper, @Value("${app.allow-unverified-standards:false}") boolean allowUnverified) {
        this(mapper, path -> new ClassPathResource("standards/" + path).getContentAsString(StandardCharsets.UTF_8), allowUnverified);
    }

    public StandardRegistry(ObjectMapper mapper, StandardLoader.Source source, boolean allowUnverified) {
        this.allowUnverified = allowUnverified;
        this.catalog = StandardLoader.loadCatalog(mapper, source);
        Map<StandardProfileId, StandardProfile> loaded = new EnumMap<>(StandardProfileId.class);
        Map<StandardProfileId, Map<String, NutrientMaster>> masters = new EnumMap<>(StandardProfileId.class);
        Map<StandardProfileId, Map<String, StandardRecord>> indexes = new EnumMap<>(StandardProfileId.class);
        for (StandardProfileId id : StandardProfileId.values()) {
            StandardProfile profile = StandardLoader.loadProfile(mapper, source, id, catalog);
            loaded.put(id, profile);
            masters.put(id, Collections.unmodifiableMap(UnitConversion.nutrientsFor(catalog, id)));
            Map<String, StandardRecord> index = new HashMap<>();
            profile.records().forEach(record -> index.put(record.nutrientId() + "/" + record.lifeStage(), record));
            indexes.put(id, Collections.unmodifiableMap(index));
        }
        this.profiles = Collections.unmodifiableMap(loaded);
        this.nutrients = Collections.unmodifiableMap(masters);
        this.recordIndex = Collections.unmodifiableMap(indexes);
    }

    public NutrientCatalog catalog() { return catalog; }

    public StandardProfile profile(StandardProfileId id) { return profiles.get(id == null ? StandardProfileId.DEFAULT : id); }

    public Map<StandardProfileId, StandardProfile> profiles() { return profiles; }

    /** 공통 44개 + 해당 프로필 전용 성분. */
    public Map<String, NutrientMaster> masterNutrients(StandardProfileId id) { return nutrients.get(id); }

    public NutrientMaster master(StandardProfileId id, String nutrientId) { return nutrients.get(id).get(nutrientId); }

    public StandardRecord record(StandardProfileId id, String nutrientId, String recordStage) {
        return recordIndex.get(id).get(nutrientId + "/" + recordStage);
    }

    /** ALLOW_UNVERIFIED_STANDARDS=true일 때만 미검증 기준값을 판정에 쓴다(테스트·개발용). */
    public boolean allowUnverifiedStandards() { return allowUnverified; }
}
