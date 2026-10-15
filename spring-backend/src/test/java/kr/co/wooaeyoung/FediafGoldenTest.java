package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * FEDIAF 2025 회귀 방지. 골든 파일은 리팩터링 전 코드로 한 번만 만든다.
 * <pre>mvnw -Dgolden.generate=true -Dtest=FediafGoldenTest test</pre>
 * 골든의 모든 키·값(부동소수점 포함)은 그대로 유지되어야 하고 새 필드 추가만 허용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FediafGoldenTest {
    private static final Path CASES = Path.of("src/test/resources/golden/fediaf_cases.json");
    private static final Path GOLDEN = Path.of("src/test/resources/golden/fediaf_2025.json");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    @EnabledIfSystemProperty(named = "golden.generate", matches = "true")
    void generate() throws Exception {
        if (Files.exists(GOLDEN) && !Boolean.getBoolean("golden.force")) fail(GOLDEN + "이 이미 있습니다. 덮어쓰려면 -Dgolden.force=true");
        Files.writeString(GOLDEN, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(collect()) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisabledIfSystemProperty(named = "golden.generate", matches = "true")
    void fediafOutputMatchesGolden() throws Exception {
        JsonNode golden = mapper.readTree(Files.readString(GOLDEN, StandardCharsets.UTF_8));
        ObjectNode current = collect();
        assertThat(iterable(golden.fieldNames())).containsExactlyInAnyOrderElementsOf(iterable(current.fieldNames()));
        for (Map.Entry<String, JsonNode> entry : iterable(golden.fields())) assertPreserved(entry.getValue(), current.get(entry.getKey()), entry.getKey());
    }

    private ObjectNode collect() throws Exception {
        JsonNode cases = mapper.readTree(Files.readString(CASES, StandardCharsets.UTF_8));
        ObjectNode result = mapper.createObjectNode();
        for (JsonNode item : cases.get("post")) {
            MockHttpServletResponse response = mvc.perform(post(item.get("path").asText()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(item.get("body")))).andReturn().getResponse();
            result.set(item.get("name").asText(), snapshot(response));
        }
        for (JsonNode item : cases.get("get")) {
            MockHttpServletResponse response = mvc.perform(get(item.get("path").asText())).andReturn().getResponse();
            result.set(item.get("name").asText(), snapshot(response));
        }
        return result;
    }

    private ObjectNode snapshot(MockHttpServletResponse response) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", response.getStatus());
        JsonNode body = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        if (body instanceof ObjectNode object) object.remove("traceId");
        node.set("body", body);
        return node;
    }

    private static void assertPreserved(JsonNode expected, JsonNode actual, String path) {
        assertThat(actual).as(path + ": 필드가 사라짐").isNotNull();
        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + ": object가 아님").isTrue();
            for (Map.Entry<String, JsonNode> entry : iterable(expected.fields()))
                assertPreserved(entry.getValue(), actual.get(entry.getKey()), path + "." + entry.getKey());
        } else if (expected.isArray()) {
            assertThat(actual.size()).as(path + ": 목록 길이").isEqualTo(expected.size());
            for (int index = 0; index < expected.size(); index++) assertPreserved(expected.get(index), actual.get(index), path + "[" + index + "]");
        } else {
            // 숫자 노드는 타입과 값이 모두 같아야 한다(근사 비교 없음).
            assertThat(actual).as(path).isEqualTo(expected);
        }
    }

    private static <T> Iterable<T> iterable(Iterator<T> iterator) { return () -> iterator; }
}
