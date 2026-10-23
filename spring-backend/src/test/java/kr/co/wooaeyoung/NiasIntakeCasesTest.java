package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.wooaeyoung.standards.NiasV3Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static kr.co.wooaeyoung.StandardProfileContractTest.row;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 계산명세서 v3 11장 T9~T11(섭취량 합산). 참조 구현은 사료 항목이 없어도 complete_feed면 추정하지만,
 * Java는 사료 항목이 있어야 추정하므로(Issue #3 결정 6) 성분 미표기 완전사료 feed-complete-unknown을 함께 보낸다.
 * T1 기준선: 비타민 D 하한 77.32 / 주의 371.15 / 상한 494.86 IU. 허용 오차는 명세 표시 자릿수 ±0.05.
 */
class NiasIntakeCasesTest {
    static final String FEED = "{\"productId\":\"feed-complete-unknown\",\"dailyAmount\":150,\"unit\":\"G\"}";
    static final String VITAMIN_D_300 = "{\"name\":\"비타민 D제\",\"type\":\"SUPPLEMENT\",\"servingAmount\":1,\"servingUnit\":\"TABLET\",\"dailyAmount\":1,"
            + "\"nutrients\":[{\"nutrientId\":\"VITAMIN_D\",\"amount\":300,\"unit\":\"IU\"}]}";

    static String t1(boolean completeFeed, String manualItems) {
        return "{\"profile\":{\"name\":\"검산\",\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":36,\"unit\":\"MONTH\"},\"completeFeed\":" + completeFeed + "},"
                + "\"items\":[" + FEED + "],\"manualItems\":[" + manualItems + "],\"standardProfile\":\"NIAS_V3\"}";
    }

    static JsonNode analyze(MockMvc mvc, ObjectMapper mapper, String body) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus(), content);
        return mapper.readTree(content);
    }

    @Nested @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
    class DefaultFeedFactor {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;

        @Test @DisplayName("T9 T1 + 완전사료, 비타민 D 300 IU × 1: total 377.3(사료 77.3 추정 + 300) → 주의")
        void t9() throws Exception {
            JsonNode d = row(analyze(mvc, mapper, t1(true, VITAMIN_D_300)), "VITAMIN_D");
            assertEquals(377.3, d.get("total").asDouble(), 0.05);
            assertEquals(77.3, d.get("fromFeed").asDouble(), 0.05);
            assertEquals(300, d.get("fromSupplements").asDouble(), 1e-12);
            assertEquals("ESTIMATED", d.get("source").asText());
            assertEquals("CAUTION", d.get("status").asText());
            assertEquals("ESTIMATED", d.get("provenance").get(0).get("source").asText());
            assertEquals("ACTUAL", d.get("provenance").get(1).get("source").asText());
            assertEquals("IU", d.get("provenance").get(1).get("conversion").get("baseUnit").asText());
        }

        @Test @DisplayName("T11 T1 + 완전사료 아님, 영양제 없음: 비타민 D total 0 → 부족(정보 없음 표시). 비타민 K는 NIAS 분석 대상이 아님")
        void t11() throws Exception {
            JsonNode analysis = analyze(mvc, mapper, t1(false, ""));
            JsonNode d = row(analysis, "VITAMIN_D");
            assertEquals(0, d.get("total").asDouble());
            assertEquals("DEFICIENT", d.get("status").asText());
            assertTrue(d.get("unknownContribution").asBoolean());
            assertTrue(analysis.get("warnings").toString().contains("사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다."));
            // 비타민 K는 nias-v3/nutrients.json에서 implemented=false라 분석 행이 없다. 성분 목록에서 기준 없음(implemented=false)만 확인한다.
            for (JsonNode row : analysis.get("nutrients")) assertNotEquals("VITAMIN_K", row.get("nutrientId").asText());
            JsonNode catalog = mapper.readTree(mvc.perform(get("/api/v1/nutrients").param("standardProfile", "NIAS_V3"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            boolean found = false;
            for (JsonNode item : catalog.get("items")) {
                if (!item.toString().contains("\"VITAMIN_K\"")) continue;
                found = true;
                assertFalse(item.path("implemented").asBoolean(true), item.toString());
            }
            assertTrue(found, "NIAS 성분 목록에 VITAMIN_K가 있어야 함");
        }
    }

    @Nested @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
    class FeedFactor27 {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;
        /** parameters.json은 바꾸지 않고 이 테스트 컨텍스트에서만 feedFactor를 2.7로 둔다. */
        @SpyBean NiasV3Strategy nias;

        @Test @DisplayName("T10 T9에서 feed_factor 2.7: total 508.8 → 과다")
        void t10() throws Exception {
            doReturn(2.7).when(nias).feedFactor();
            JsonNode d = row(analyze(mvc, mapper, t1(true, VITAMIN_D_300)), "VITAMIN_D");
            assertEquals(508.8, d.get("total").asDouble(), 0.05);
            assertEquals("EXCESS", d.get("status").asText());
            assertTrue(d.get("provenance").get(0).get("note").asText().contains("2.7"));
        }
    }
}
