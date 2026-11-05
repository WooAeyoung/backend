package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.wooaeyoung.api.ErrorReason;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static kr.co.wooaeyoung.api.ErrorReason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 오류 계약(Issue #5, BE-V3-015). 기존 detail.code·message·HTTP 상태는 그대로이고 detail.reason만 더해진다.
 *
 * <ul>
 *   <li>contract/errors.json 26개 요청의 응답이 전환 전 스냅숏(3cd1049)과 같다. 다른 점은 reason 하나뿐이다.
 *       detail의 키 순서는 전환 전 Map.of라 JVM마다 달랐으므로 code, message 순으로 맞춘다(결정 D4).
 *       VALIDATION_ERROR 메시지는 JVM 로캘을 따르므로 code와 reason 없음만 본다.</li>
 *   <li>소스의 모든 AnalysisException 발생 지점과, AnalysisException으로 감싸지는 UnitConversionService의 IllegalArgumentException
 *       발생 지점이 아래 표에 정확히 한 번씩 대응하고, 표의 예시 메시지가 INPUT_ERROR가 아닌 사유로 분류된다(결정 D2).
 *       새 발생 지점이 생기면 이 테스트가 실패하므로 표와 ErrorReason 패턴을 함께 고친다.</li>
 *   <li>분류되지 않는 메시지는 INPUT_ERROR로 떨어진다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorReasonContractTest {
    private static final Path MAIN = Path.of("src", "main", "java", "kr", "co", "wooaeyoung");
    private static final ObjectMapper PLAIN = new ObjectMapper();

    @Autowired MockMvc mvc;

    /** 발생 지점: 파일(MAIN 기준) · 그 줄에 있는 메시지 조각 · 실제 형식의 예시 메시지 · 기대 사유. */
    record Site(String file, String snippet, String sample, ErrorReason reason) {}

    static final List<Site> SITES = List.of(
            new Site("domain/CatalogService.java", "\"제품을 찾을 수 없습니다: \"", "제품을 찾을 수 없습니다: no-such-product", PRODUCT_NOT_FOUND),
            new Site("domain/CatalogService.java", "\"바코드 제품을 찾을 수 없습니다: \"", "바코드 제품을 찾을 수 없습니다: 8800000000000", PRODUCT_NOT_FOUND),
            new Site("domain/NutritionService.java", "\"분석할 사료 또는 영양제를 하나 이상 추가해주세요.\"", "분석할 사료 또는 영양제를 하나 이상 추가해주세요.", NO_ITEMS),
            new Site("domain/NutritionService.java", "\"제품은 최대 20개까지 분석할 수 있습니다.\"", "제품은 최대 20개까지 분석할 수 있습니다.", TOO_MANY_ITEMS),
            new Site("domain/NutritionService.java", "\"의 급여 단위는 \"", "데일리 밸런스 독의 급여 단위는 G입니다.", UNIT_MISMATCH),
            new Site("domain/NutritionService.java", "\"중복 성분입니다: \"", "중복 성분입니다: ZINC", DUPLICATE_NUTRIENT),
            new Site("domain/NutritionService.java", "\"지원하지 않는 성분입니다: \"", "지원하지 않는 성분입니다: UNOBTAINIUM", NUTRIENT_UNSUPPORTED),
            new Site("domain/NutritionService.java", "\"변환할 수 없는 단위입니다: \"", "변환할 수 없는 단위입니다: IU → MG", UNIT_UNSUPPORTED),
            new Site("domain/NiasIntakeAggregator.java", "\"의 급여 단위는 \"", "데일리 밸런스 독의 급여 단위는 G입니다.", UNIT_MISMATCH),
            new Site("domain/NiasIntakeAggregator.java", "\"중복 성분입니다: \"", "중복 성분입니다: ZINC", DUPLICATE_NUTRIENT),
            new Site("domain/NiasIntakeAggregator.java", "\"지원하지 않는 성분입니다: \"", "지원하지 않는 성분입니다: UNOBTAINIUM", NUTRIENT_UNSUPPORTED),
            new Site("domain/NiasIntakeAggregator.java", "\"변환할 수 없는 단위입니다: \"", "변환할 수 없는 단위입니다: IU → mg", UNIT_UNSUPPORTED),
            // UnitConversionService의 IllegalArgumentException 메시지를 그대로 감싼다. 메시지 분류는 아래 units/ 지점이 맡는다.
            new Site("domain/NiasIntakeAggregator.java", "new AnalysisException(error.getMessage())", null, null),
            new Site("standards/FediafStrategy.java", "\"8주 미만 개체는 현재 지원하지 않습니다.\"", "8주 미만 개체는 현재 지원하지 않습니다.", AGE_UNSUPPORTED),
            new Site("standards/FediafStrategy.java", "\"12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.\"", "12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.", EXPECTED_ADULT_WEIGHT_REQUIRED),
            new Site("standards/NiasV3Strategy.java", "\"주 미만 개체는 현재 지원하지 않습니다.\"", "8주 미만 개체는 현재 지원하지 않습니다.", AGE_UNSUPPORTED),
            new Site("standards/NiasV3Strategy.java", "\"체급 선택 필요: ", "체급 선택 필요: 성장기 강아지는 체급(adultSize: S, M, L, XL, XXL) 선택이 필요합니다.", SIZE_CLASS_REQUIRED),
            new Site("standards/NiasV3Strategy.java", "\"지원하지 않는 체급입니다: \"", "지원하지 않는 체급입니다: Q (S, M, L, XL, XXL)", SIZE_CLASS_INVALID),
            new Site("units/UnitConversionService.java", "\"알 수 없는 성분: \"", "알 수 없는 성분: UNOBTAINIUM", NUTRIENT_UNSUPPORTED),
            new Site("units/UnitConversionService.java", "\"함량은 0 이상의 유한한 값이어야 합니다: \"", "함량은 0 이상의 유한한 값이어야 합니다: ZINC=-1.0", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\"원료 형태 \"", "원료 형태 RETINOL는 VITAMIN_A 성분용입니다: VITAMIN_D", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\"수분(%)은 0 이상 100 미만이어야 합니다: \"", "수분(%)은 0 이상 100 미만이어야 합니다: 120.0", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\"EPA와 DHA의 표기 기준(1 g당/1회 제공량당)이 다릅니다.\"", "EPA와 DHA의 표기 기준(1 g당/1회 제공량당)이 다릅니다.", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\"EPA_DHA와 EPA·DHA를 함께 둘 수 없음\"", "EPA_DHA와 EPA·DHA를 함께 둘 수 없음", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\"BETA_CAROTENE의 원료 형태는 BETA_CAROTENE뿐입니다: \"", "BETA_CAROTENE의 원료 형태는 BETA_CAROTENE뿐입니다: RETINOL", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\"VITAMIN_A와 BETA_CAROTENE의 표기 기준(1 g당/1회 제공량당)이 다릅니다.\"", "VITAMIN_A와 BETA_CAROTENE의 표기 기준(1 g당/1회 제공량당)이 다릅니다.", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\": % 단위는 PERCENT 또는 DRY_MATTER_PERCENT 표기에만 씁니다(\"", "ZINC: % 단위는 PERCENT 또는 DRY_MATTER_PERCENT 표기에만 씁니다(PER_SERVING)", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\" 표기는 % 단위여야 합니다(\"", "ZINC: PERCENT 표기는 % 단위여야 합니다(mg)", LABEL_INVALID),
            new Site("units/UnitConversionService.java", "\": ppm은 PER_KG(mg/kg) 표기입니다(\"", "ZINC: ppm은 PER_KG(mg/kg) 표기입니다(PERCENT)", LABEL_INVALID));

    /** AnalysisException 메시지가 되는 발생 지점: 모든 AnalysisException, 그리고 감싸지는 UnitConversionService의 IllegalArgumentException. */
    static List<String[]> scanSites() throws IOException {
        List<String[]> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    boolean analysis = line.contains("new AnalysisException(");
                    boolean wrappedLabel = relative.equals("units/UnitConversionService.java") && line.contains("new IllegalArgumentException(");
                    if (analysis || wrappedLabel) result.add(new String[]{relative, String.valueOf(i + 1), line.trim()});
                }
            }
        }
        return result;
    }

    @Test void everyThrowSiteIsClassifiedExactlyOnce() throws IOException {
        List<String> errors = new ArrayList<>();
        Map<Site, Integer> used = new LinkedHashMap<>();
        SITES.forEach(site -> used.put(site, 0));
        List<String[]> sites = scanSites();
        for (String[] site : sites) {
            List<Site> matches = SITES.stream().filter(entry -> entry.file().equals(site[0]) && site[2].contains(entry.snippet())).toList();
            if (matches.size() != 1) errors.add(site[0] + ":" + site[1] + " 표 대응 " + matches.size() + "개: " + site[2]);
            else used.merge(matches.get(0), 1, Integer::sum);
        }
        used.forEach((entry, count) -> { if (count == 0) errors.add("소스에 없는 표 항목: " + entry.file() + " " + entry.snippet()); });
        for (Site entry : SITES) {
            if (entry.sample() == null) continue;
            ErrorReason actual = ErrorReason.ofCalculationMessage(entry.sample());
            if (actual != entry.reason()) errors.add(entry.sample() + " → " + actual + " (기대 " + entry.reason() + ")");
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
        assertEquals(30, sites.size(), "발생 지점 수가 바뀌었다. 표와 ErrorReason 패턴을 함께 확인한다.");
    }

    @Test void unclassifiedMessagesFallBackToInputError() {
        assertEquals(INPUT_ERROR, ErrorReason.ofCalculationMessage("새로 생긴 계산 오류 메시지"));
        assertEquals(INPUT_ERROR, ErrorReason.ofCalculationMessage(""));
        assertEquals(INPUT_ERROR, ErrorReason.ofCalculationMessage(null));
        // 앞뒤 문맥이 다르면 다른 사유로 오인하지 않는다.
        assertEquals(INPUT_ERROR, ErrorReason.ofCalculationMessage("최근 8주 미만 개체 데이터가 부족합니다"));
    }

    @TestFactory Stream<DynamicTest> errorResponsesKeepExistingCodeMessageAndStatusAndAddOnlyReason() throws IOException {
        JsonNode cases = PLAIN.readTree(Files.readString(ResponseContractSnapshotTest.CONTRACT.resolve("errors.json"), StandardCharsets.UTF_8));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) tests.add(DynamicTest.dynamicTest(c.get("id").asText(), () -> {
            String path = c.get("path").asText();
            String content = c.has("rawBody") ? c.get("rawBody").asText() : c.has("body") ? PLAIN.writeValueAsString(c.get("body")) : null;
            MockHttpServletResponse response = mvc.perform(c.get("method").asText().equals("GET") ? get(path)
                    : post(path).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
            assertEquals(c.get("status").asInt(), response.getStatus(), "HTTP 상태");
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            JsonNode before = PLAIN.readTree(ResponseContractSnapshotTest.snapshot(c.get("id").asText())).get("detail");
            JsonNode after = PLAIN.readTree(body).get("detail");
            assertEquals(c.get("code").asText(), before.get("code").asText(), "스냅숏의 기존 code");
            String reason = c.get("reason").isNull() ? null : c.get("reason").asText();
            if (c.get("code").asText().equals("VALIDATION_ERROR")) {
                assertEquals(before.get("code"), after.get("code"));
                assertFalse(after.has("reason"));
                return;
            }
            ObjectNode expected = PLAIN.createObjectNode();
            expected.set("code", before.get("code"));
            expected.set("message", before.get("message"));
            if (reason != null) expected.put("reason", reason);
            assertEquals(PLAIN.writeValueAsString(PLAIN.createObjectNode().set("detail", expected)), body);
        }));
        assertEquals(26, tests.size());
        return tests.stream();
    }
}
