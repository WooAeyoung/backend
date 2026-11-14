package kr.co.wooaeyoung.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.contract.ResponseContractSnapshotTest.Case;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static kr.co.wooaeyoung.contract.ResponseContractSnapshotTest.cases;
import static kr.co.wooaeyoung.contract.ResponseContractSnapshotTest.maskTraceId;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 재현성(Issue #5, BE-V3-019): 같은 버전·같은 입력이면 UUID를 뺀 응답이 바이트 단위로 같다.
 * contract/requests.json 60개 요청(FEDIAF_2025·NIAS_V3, 분석·추천·조회, 기본·운영 차단 설정)을 같은 서버에 두 번 보낸다.
 * traceId는 입력 해시 16자가 같아야 하고 UUID 8자만 달라도 된다. 시각·난수·해시 순서가 응답에 섞이면 실패한다.
 * JVM이 달라도 같은지는 ResponseContractSnapshotTest(다른 JVM에서 뜬 스냅숏과 바이트 비교)가 본다.
 */
class DeterminismTest {
    private static final Pattern TRACE = Pattern.compile("\"traceId\":\"([0-9a-f]{16})-([0-9a-f]{8})\"");

    @Test void requestsCoverBothProfilesForAnalysisAndRecommendation() {
        Set<String> covered = new TreeSet<>();
        for (String mode : List.of("default", "strict"))
            for (Case c : cases(mode)) {
                if (c.body() == null) continue;
                String profile = c.body().has("standardProfile") ? c.body().get("standardProfile").asText() : "FEDIAF_2025(생략)";
                covered.add(mode + " " + c.path() + " " + profile);
            }
        for (String mode : List.of("default", "strict"))
            for (String path : List.of("/api/v1/analyses", "/api/v1/recommendations"))
                for (String profile : List.of("FEDIAF_2025(생략)", "NIAS_V3"))
                    assertTrue(covered.contains(mode + " " + path + " " + profile), mode + " " + path + " " + profile);
    }

    abstract static class Cases {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;

        abstract String mode();

        @TestFactory Stream<DynamicTest> sameInputGivesSameResponseExceptTraceIdUuid() {
            return cases(mode()).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
                String first = call(c), second = call(c);
                assertEquals(maskTraceId(first), maskTraceId(second), c.id());
                Matcher a = TRACE.matcher(first), b = TRACE.matcher(second);
                if (a.find()) {
                    assertTrue(b.find(), c.id());
                    assertEquals(a.group(1), b.group(1), c.id() + " traceId 입력 해시");
                    assertFalse(a.find(), c.id() + " traceId는 하나");
                } else assertFalse(b.find(), c.id());
            }));
        }

        private String call(Case c) throws Exception {
            var response = mvc.perform(c.method().equals("GET") ? get(c.path())
                    : post(c.path()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(c.body()))).andReturn().getResponse();
            assertEquals(200, response.getStatus(), c.id());
            return response.getContentAsString(StandardCharsets.UTF_8);
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class Default extends Cases {
        @Override String mode() { return "default"; }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "wooaeyoung.standards.allow-unverified=false")
    class Strict extends Cases {
        @Override String mode() { return "strict"; }
    }
}
