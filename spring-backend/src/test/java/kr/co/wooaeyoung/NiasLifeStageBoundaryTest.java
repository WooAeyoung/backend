package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static kr.co.wooaeyoung.StandardProfileContractTest.request;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * NIAS_V3 생애주기 경계(BE-V3-005, 계산명세서 1·2·12장).
 * 같은 케이스를 운영 설정(allow-unverified=false)과 test 프로필(allow-unverified=true)에서 모두 확인한다.
 * UNSUPPORTED는 8주 미만이며 현재 Java 응답(422 CALCULATION_INPUT_ERROR)을 유지한다.
 */
class NiasLifeStageBoundaryTest {
    static final String DOG_FEED = "{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":100,\"unit\":\"G\"}";
    static final String CAT_FEED = "{\"productId\":\"feed-balanced-cat\",\"dailyAmount\":40,\"unit\":\"G\"}";

    abstract static class Cases {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;

        @ParameterizedTest(name = "{0} {1} {2} → {3}")
        @CsvSource({
                "DOG, 7.99,   WEEK,  UNSUPPORTED",
                "DOG, 8,      WEEK,  GROWTH_EARLY",
                "DOG, 13.99,  WEEK,  GROWTH_EARLY",
                "DOG, 14,     WEEK,  GROWTH_LATE",
                "DOG, 3,      MONTH, GROWTH_EARLY",   // 3 × 4.345 = 13.035주
                "DOG, 13.035, WEEK,  GROWTH_EARLY",
                "DOG, 11.99,  MONTH, GROWTH_LATE",
                "DOG, 12,     MONTH, ADULT",
                "DOG, 52.14,  WEEK,  ADULT",          // 12 × 4.345
                "CAT, 7.99,   WEEK,  UNSUPPORTED",
                "CAT, 8,      WEEK,  CAT_S1",
                "CAT, 4,      MONTH, CAT_S1",
                "CAT, 17.38,  WEEK,  CAT_S1",         // 4 × 4.345
                "CAT, 4.01,   MONTH, CAT_S2",
                "CAT, 9,      MONTH, CAT_S2",
                "CAT, 39.105, WEEK,  CAT_S2",         // 9 × 4.345
                "CAT, 9.01,   MONTH, CAT_S3",
                "CAT, 11.99,  MONTH, CAT_S3",
                "CAT, 12,     MONTH, ADULT",
                "CAT, 52.14,  WEEK,  ADULT",
        })
        void lifeStageBoundary(String species, double age, String unit, String expected) throws Exception {
            boolean dog = species.equals("DOG");
            // 성장기 개는 체급이 필수라 경계 판정만 보도록 M을 넣는다. 성체·고양이에서는 무시된다.
            String body = request(species, dog ? 5 : 2, age, unit, dog ? ",\"adultSize\":\"M\"" : "", dog ? DOG_FEED : CAT_FEED, "NIAS_V3");
            MockHttpServletResponse response = mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            JsonNode json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            if (expected.equals("UNSUPPORTED")) {
                assertEquals(422, response.getStatus(), json.toString());
                assertEquals("CALCULATION_INPUT_ERROR", json.get("detail").get("code").asText());
                assertTrue(json.get("detail").get("message").asText().contains("8주 미만"), json.toString());
            } else {
                assertEquals(200, response.getStatus(), json.toString());
                assertEquals(expected, json.get("lifeStage").asText());
            }
        }

        @ParameterizedTest(name = "개 {0} {1}, 체급 {2} → 422")
        @CsvSource(value = {"12, WEEK, ''", "3, MONTH, ''", "11.99, MONTH, ''", "12, WEEK, MEDIUM", "12, WEEK, s"}, emptyValue = "")
        void growingDogRequiresKnownSizeClass(double age, String unit, String size) throws Exception {
            String extra = size.isEmpty() ? "" : ",\"adultSize\":\"" + size + "\"";
            MockHttpServletResponse response = mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
                    .content(request("DOG", 3, age, unit, extra, DOG_FEED, "NIAS_V3"))).andReturn().getResponse();
            JsonNode json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(422, response.getStatus(), json.toString());
            assertEquals("CALCULATION_INPUT_ERROR", json.get("detail").get("code").asText());
            String message = json.get("detail").get("message").asText();
            assertTrue(size.isEmpty() ? message.startsWith("체급 선택 필요") : message.startsWith("지원하지 않는 체급입니다: " + size), message);
        }

        @ParameterizedTest(name = "체급 없음 허용: {0} {1} {2}")
        @CsvSource({"DOG, 12, MONTH", "CAT, 3, MONTH"})
        void adultDogAndCatDoNotNeedSizeClass(String species, double age, String unit) throws Exception {
            MockHttpServletResponse response = mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
                    .content(request(species, 3, age, unit, "", species.equals("DOG") ? DOG_FEED : CAT_FEED, "NIAS_V3"))).andReturn().getResponse();
            assertEquals(200, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class Production extends Cases {}

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class AllowUnverified extends Cases {}
}
