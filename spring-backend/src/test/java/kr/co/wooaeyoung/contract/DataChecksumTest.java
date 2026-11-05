package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.standards.DataChecksums;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 데이터 체크섬(Issue #5, BE-V3-019).
 * <ul>
 *   <li>시작 시 계산한 값이 기록 파일(contract/data-checksums.json, git index의 LF 내용으로 계산)과 같다.</li>
 *   <li>Windows 체크아웃(CRLF)과 CI·Docker 체크아웃(LF)에서 같은 값이 나온다: 각 파일을 LF판과 CRLF판으로 만들어 둘 다 기록 값과 같은지 본다.
 *       정규화 없이 CRLF판을 해시하면 값이 달라진다는 것도 확인해, 이 테스트가 줄바꿈 차이를 실제로 잡는지 보인다.</li>
 *   <li>분석·추천·조회 응답의 checksums가 이 값을 그대로 보여 준다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataChecksumTest {
    private static final ObjectMapper PLAIN = new ObjectMapper();
    private static final String ADULT_DOG = "{\"profile\":{\"name\":\"체크섬\",\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":36,\"unit\":\"MONTH\"}},"
            + "\"items\":[{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":150,\"unit\":\"G\"}]";

    @Autowired DataChecksums checksums;
    @Autowired MockMvc mvc;

    static Map<String, String> recorded() throws IOException {
        JsonNode files = PLAIN.readTree(Files.readString(ResponseContractSnapshotTest.CONTRACT.resolve("data-checksums.json"), StandardCharsets.UTF_8)).get("files");
        Map<String, String> result = new LinkedHashMap<>();
        files.fields().forEachRemaining(field -> result.put(field.getKey(), field.getValue().asText()));
        return result;
    }

    @Test void startupChecksumsMatchRecordedValues() throws IOException {
        assertEquals(recorded(), checksums.files());
        assertEquals("SHA-256 (CRLF→LF)", DataChecksums.ALGORITHM);
    }

    @Test void lfAndCrlfCheckoutsGiveTheSameRecordedValue() throws Exception {
        for (Map.Entry<String, String> entry : recorded().entrySet()) {
            String text;
            try (InputStream input = new ClassPathResource(entry.getKey()).getInputStream()) { text = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            byte[] lf = text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            byte[] crlf = text.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
            assertTrue(new String(lf, StandardCharsets.UTF_8).contains("\n"), entry.getKey() + "에 줄바꿈이 없어 검사가 의미 없음");
            String where = entry.getKey();
            assertEquals(entry.getValue(), DataChecksums.sha256(lf), where + " LF(CI·Docker)");
            assertEquals(entry.getValue(), DataChecksums.sha256(crlf), where + " CRLF(Windows core.autocrlf=true)");
            // 기록 값은 정규화 규칙과 무관하게 LF 내용(git blob 내용)의 순수 SHA-256이다.
            assertEquals(entry.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(lf)), where + " 순수 SHA-256");
            // 정규화하지 않으면 CRLF판의 값이 달라진다(이 테스트가 잡으려는 차이).
            assertNotEquals(entry.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(crlf)), where + " 정규화 없는 CRLF");
        }
    }

    @Test void onlyCrlfPairsAreNormalized() {
        assertEquals(DataChecksums.sha256("a\nb".getBytes(StandardCharsets.UTF_8)), DataChecksums.sha256("a\r\nb".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(DataChecksums.sha256("ab".getBytes(StandardCharsets.UTF_8)), DataChecksums.sha256("a\rb".getBytes(StandardCharsets.UTF_8)), "홀로 있는 CR은 그대로 둔다");
        assertEquals(DataChecksums.sha256("a\n".getBytes(StandardCharsets.UTF_8)), DataChecksums.sha256("a\r\n".getBytes(StandardCharsets.UTF_8)), "파일 끝 CRLF");
    }

    @Test void responsesExposeTheChecksumsOfTheProfileTheyUsed() throws Exception {
        Map<String, String> files = recorded();
        JsonNode nias = body(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(ADULT_DOG + ",\"standardProfile\":\"NIAS_V3\"}"));
        assertChecksums(nias.get("checksums"), files, "nias-v3", files.get("units/conversion-factors.json"));
        JsonNode fediaf = body(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(ADULT_DOG + "}"));
        assertChecksums(fediaf.get("checksums"), files, "fediaf-2025", null);
        JsonNode recommendation = body(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(ADULT_DOG + ",\"standardProfile\":\"NIAS_V3\"}"));
        assertEquals(nias.get("checksums"), recommendation.get("checksums"));
        assertEquals(nias.get("checksums"), body(get("/api/v2/standards/NIAS_V3")).get("checksums"));
        assertEquals(nias.get("checksums"), body(get("/api/v2/parameters/NIAS_V3")).get("checksums"));
        assertEquals(fediaf.get("checksums"), body(get("/api/v2/parameters/FEDIAF_2025")).get("checksums"));
    }

    private static void assertChecksums(JsonNode actual, Map<String, String> files, String directory, String units) {
        assertEquals(List.of("algorithm", "standards", "parameters", "nutrientMaster", "units", "products"), fieldNames(actual));
        assertEquals(DataChecksums.ALGORITHM, actual.get("algorithm").asText());
        assertEquals(files.get("standards/" + directory + "/nutrients.json"), actual.get("standards").asText());
        assertEquals(files.get("standards/" + directory + "/parameters.json"), actual.get("parameters").asText());
        assertEquals(files.get("standards/nutrients-master.json"), actual.get("nutrientMaster").asText());
        if (units == null) assertTrue(actual.get("units").isNull()); else assertEquals(units, actual.get("units").asText());
        assertEquals(files.get("products.json"), actual.get("products").asText());
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return PLAIN.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
