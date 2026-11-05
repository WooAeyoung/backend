package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static kr.co.wooaeyoung.contract.DataChecksumTest.fieldNames;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 조회 API(Issue #5, BE-V3-016). 더한 값이 데이터 파일과 같고(지어낸 값 없음), 프런트가 하드코딩 없이 쓸 수 있는지 본다.
 * 기존 필드가 그대로인지는 ResponseContractSnapshotTest가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LookupApiContractTest {
    private static final ObjectMapper PLAIN = new ObjectMapper();

    @Autowired MockMvc mvc;

    @Test void parametersEndpointReturnsTheParameterFileVerbatim() throws Exception {
        for (String[] profile : List.of(new String[]{"NIAS_V3", "nias-v3"}, new String[]{"FEDIAF_2025", "fediaf-2025"})) {
            JsonNode response = body("/api/v2/parameters/" + profile[0]);
            assertEquals(List.of("standardProfile", "standardVersion", "parameterVersion", "verification", "allowUnverified", "parameters", "checksums"), fieldNames(response));
            JsonNode file = resource("standards/" + profile[1] + "/parameters.json");
            // 값과 키 순서까지 파일과 같다(트리 비교 + 직렬화 비교).
            assertEquals(file, response.get("parameters"), profile[0]);
            assertEquals(PLAIN.writeValueAsString(file), PLAIN.writeValueAsString(response.get("parameters")), profile[0] + " 키 순서");
            assertEquals(file.get("parameterVersion"), response.get("parameterVersion"));
        }
        JsonNode sizes = body("/api/v2/parameters/NIAS_V3").get("parameters").get("growthCurve").get("sizeClasses");
        assertEquals(List.of("S", "M", "L", "XL", "XXL"), fieldNames(sizes), "체급 선택지(adultSize)는 파일 순서 그대로");
        assertTrue(sizes.get("XXL").get("upperKg").isNull());
    }

    @Test void niasStandardsExposeLabelInputFromTheConversionTable() throws Exception {
        JsonNode response = body("/api/v2/standards/NIAS_V3");
        assertEquals(List.of("standardVersion", "standardProfile", "parameterVersion", "parameterVerification", "standardsVerification", "items", "labelInput", "checksums"),
                fieldNames(response));
        JsonNode input = response.get("labelInput");
        JsonNode file = resource("units/conversion-factors.json");
        assertEquals(file.get("version"), input.get("version"));
        assertEquals(file.get("sourceDocument"), input.get("sourceDocument"));
        List<String> aliases = new ArrayList<>(), units = new ArrayList<>();
        file.get("unitAliases").forEach(alias -> aliases.add(alias.get("alias").asText() + "→" + alias.get("unit").asText()));
        input.get("units").forEach(unit -> units.add(unit.get("alias").asText() + "→" + unit.get("unit").asText()));
        assertEquals(aliases, units);
        List<String> fileBases = new ArrayList<>(), bases = new ArrayList<>();
        file.get("labelBases").forEach(basis -> fileBases.add(basis.get("basis").asText() + "/" + basis.get("perGram").asBoolean() + "/" + basis.get("requires").asText(null)));
        input.get("bases").forEach(basis -> bases.add(basis.get("basis").asText() + "/" + basis.get("perGram").asBoolean() + "/" + basis.get("requires").asText(null)));
        assertEquals(fileBases, bases);
        int formCount = 0;
        for (JsonNode factor : file.get("iuFactors")) {
            JsonNode options = input.get("forms").get(factor.get("nutrient").asText());
            boolean found = false;
            for (JsonNode option : options)
                found |= option.get("form").equals(factor.get("form")) && option.get("species").equals(factor.get("species"))
                        && option.get("excluded").equals(factor.get("excluded")) && option.get("assumed").equals(factor.get("assumed"));
            assertTrue(found, factor.toString());
            formCount++;
        }
        int options = 0;
        for (JsonNode list : input.get("forms")) options += list.size();
        assertEquals(formCount, options);
        assertEquals(List.of("VITAMIN_A", "VITAMIN_D", "VITAMIN_E"), fieldNames(input.get("forms")));
        assertEquals(PLAIN.readTree("[\"BETA_CAROTENE\",\"DHA\",\"EPA\",\"OMEGA3\"]"), input.get("labelOnlyNutrients"));
    }

    @Test void fediafStandardsHaveNoLabelInputAndV1CatalogIsUnchanged() throws Exception {
        JsonNode fediaf = body("/api/v2/standards/FEDIAF_2025");
        assertTrue(fediaf.has("labelInput") && fediaf.get("labelInput").isNull(), "FEDIAF_2025는 환산표를 쓰지 않아 null");
        for (String profile : List.of("NIAS_V3", "FEDIAF_2025")) {
            JsonNode v1 = body("/api/v1/nutrients?standardProfile=" + profile);
            assertFalse(v1.has("labelInput") || v1.has("checksums"), "v1 조회는 그대로");
            assertEquals(body("/api/v2/standards/" + profile).get("items"), v1.get("items"));
        }
    }

    @Test void supportedNutrientsCarryNameUnitImplementationAndSource() throws Exception {
        for (JsonNode item : body("/api/v2/standards/NIAS_V3").get("items")) {
            for (String field : List.of("id", "name", "unit", "standardUnit", "implemented")) assertFalse(item.get(field).isNull(), item.get("id") + "." + field);
            for (String line : List.of("minimum", "maximum", "caution"))
                item.get(line).forEach(value -> {
                    assertFalse(value.get("sourceDocument").isNull());
                    assertFalse(value.get("verificationStatus").isNull());
                });
        }
    }

    private JsonNode body(String path) throws Exception {
        return PLAIN.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode resource(String location) throws IOException {
        try (InputStream input = new ClassPathResource(location).getInputStream()) { return PLAIN.readTree(input); }
    }
}
