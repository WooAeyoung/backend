package kr.co.wooaeyoung.standards;

import kr.co.wooaeyoung.domain.ProductDataRegistry;
import kr.co.wooaeyoung.units.UnitConversionFactors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 기준 데이터·매개변수·환산표·제품 파일의 체크섬(BE-V3-019). 시작 시 한 번 계산한다.
 * 줄바꿈 CRLF를 LF로 바꾼 바이트의 SHA-256이다. 저장소 index는 LF이고 Windows 체크아웃(core.autocrlf=true)은 CRLF라,
 * 원본 바이트를 그대로 해시하면 같은 커밋에서도 Windows와 CI(Linux·Docker)의 값이 달라진다. 정규화한 값은 git blob 내용의 해시와 같다.
 * 기록 값은 src/test/resources/contract/data-checksums.json에 있고 DataChecksumTest가 대조한다.
 */
@Component
public class DataChecksums {
    public static final String ALGORITHM = "SHA-256 (CRLF→LF)";

    private final String productsLocation;
    private final Map<String, String> files;

    public DataChecksums(@Value("${wooaeyoung.catalog.products-resource:" + ProductDataRegistry.DEFAULT_LOCATION + "}") String productsLocation) {
        this.productsLocation = productsLocation;
        List<String> locations = new ArrayList<>(List.of(NutrientMaster.LOCATION));
        for (StandardProfile profile : StandardProfile.values()) {
            locations.add(nutrientsLocation(profile));
            locations.add(parametersLocation(profile));
        }
        locations.add(UnitConversionFactors.LOCATION);
        locations.add(productsLocation);
        Map<String, String> result = new LinkedHashMap<>();
        for (String location : locations) result.put(location, sha256(read(location)));
        this.files = Collections.unmodifiableMap(result);
    }

    /** 파일 위치(classpath) → 체크섬. 순서: 마스터, 프로필별 기준값·매개변수, 환산표, 제품. */
    public Map<String, String> files() { return files; }

    public String nutrientMaster() { return files.get(NutrientMaster.LOCATION); }
    public String standards(StandardProfile profile) { return files.get(nutrientsLocation(profile)); }
    public String parameters(StandardProfile profile) { return files.get(parametersLocation(profile)); }
    public String units() { return files.get(UnitConversionFactors.LOCATION); }
    public String products() { return files.get(productsLocation); }

    public static String nutrientsLocation(StandardProfile profile) { return "standards/" + profile.directory() + "/nutrients.json"; }
    public static String parametersLocation(StandardProfile profile) { return "standards/" + profile.directory() + "/parameters.json"; }

    /** CRLF → LF로 바꾼 뒤 SHA-256(소문자 16진수). 홀로 있는 CR은 바꾸지 않는다. */
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalizeLineEndings(bytes))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    static byte[] normalizeLineEndings(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
            out.write(bytes[i]);
        }
        return out.toByteArray();
    }

    private static byte[] read(String location) {
        try (InputStream input = new ClassPathResource(location).getInputStream()) { return input.readAllBytes(); }
        catch (IOException error) { throw new IllegalStateException("체크섬 대상 파일을 읽을 수 없습니다: " + location, error); }
    }
}
