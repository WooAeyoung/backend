package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * NIAS 추천 API(BE-V3-013). allow-unverified=false면 지금처럼 차단하고(recommendationUnsupportedReason),
 * 검증용 설정(test 프로필)에서는 새 점수식 응답을 기존 최상위 필드 순서 그대로 돌려준다.
 */
class NiasRecommendationGuardTest {
    static final String ADULT_DOG = "{\"profile\":{\"name\":\"검산\",\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":36,\"unit\":\"MONTH\"},\"completeFeed\":true},"
            + "\"items\":[{\"productId\":\"feed-balanced-dog\",\"dailyAmount\":150,\"unit\":\"G\"},{\"productId\":\"supp-zinc\",\"dailyAmount\":1,\"unit\":\"TABLET\"}],"
            + "\"standardProfile\":\"NIAS_V3\"}";
    static final String REFERENCE_ONLY = "참고값: NIAS-v3 기준값·추천 매개변수는 원문 대조 전이라 추천 결과는 검증되지 않은 참고 정보입니다.";
    /** 분기 전 NIAS 추천 응답의 최상위 필드 순서(차단·계산 모두 같음). */
    static final List<String> TOP_LEVEL = List.of("traceId", "standardVersion", "message", "items", "excluded", "usesEstimatedFeed",
            "standardProfile", "parameterVersion", "parameterVerification", "standardsVerification", "warnings");

    static JsonNode recommend(MockMvc mvc, ObjectMapper mapper, String body) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus(), content);
        return mapper.readTree(content);
    }

    static List<String> fields(JsonNode node) { List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); return names; }

    @Nested @SpringBootTest(properties = "wooaeyoung.standards.allow-unverified=false") @AutoConfigureMockMvc
    class Blocked {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;

        @Test void niasRecommendationStaysBlocked() throws Exception {
            JsonNode result = recommend(mvc, mapper, ADULT_DOG);
            assertEquals(TOP_LEVEL, fields(result));
            assertEquals(0, result.get("items").size());
            assertEquals(0, result.get("excluded").size());
            assertEquals("현재 기준 프로필에서는 추천 후보를 계산하지 않습니다.", result.get("message").asText());
            assertEquals(List.of("NIAS_V3 추천은 기준 매개변수 원문 대조가 끝난 뒤 제공됩니다."),
                    mapper.convertValue(result.get("warnings"), List.class));
        }
    }

    @Nested @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
    class AllowUnverified {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;

        @Test void niasRecommendationReturnsScoreDetails() throws Exception {
            JsonNode result = recommend(mvc, mapper, ADULT_DOG);
            assertEquals(TOP_LEVEL, fields(result));
            // 기존 NIAS 추천 경로의 warnings는 빈 목록이었다. 검증 대기 값으로 계산했으므로 참고값 경고만 맨 앞에 붙는다.
            assertEquals(List.of(REFERENCE_ONLY), mapper.convertValue(result.get("warnings"), List.class));
            Set<String> ids = new HashSet<>();
            for (JsonNode item : result.get("items")) {
                assertTrue(ids.add(item.get("productId").asText()), "중복 추천: " + item);
                assertNotEquals("supp-zinc", item.get("productId").asText());
                for (String field : List.of("productId", "name", "dailyAmount", "unit", "score", "fixedNutrients", "margin", "overlap",
                        "scoreBreakdown", "projectedStatuses", "fixedNutrientIds", "marginNutrientId", "overlapNutrientIds", "doseBand"))
                    assertTrue(item.has(field), field + " 없음: " + item);
                JsonNode breakdown = item.get("scoreBreakdown");
                for (String field : List.of("fixedNutrients", "margin", "overlap", "w2", "w3", "w4", "fixedScore", "marginScore", "overlapPenalty"))
                    assertTrue(breakdown.has(field), field + " 없음: " + breakdown);
                assertEquals(item.get("score").asDouble(),
                        breakdown.get("fixedScore").asDouble() + breakdown.get("marginScore").asDouble() - breakdown.get("overlapPenalty").asDouble(), 1e-12);
            }
            boolean fed = false;
            for (JsonNode entry : result.get("excluded")) {
                for (String field : List.of("productId", "name", "reason", "reasonCode", "reasons")) assertTrue(entry.has(field), field + " 없음: " + entry);
                if (entry.get("productId").asText().equals("supp-zinc")) { fed = true; assertEquals("ALREADY_FED", entry.get("reasonCode").asText()); }
            }
            assertTrue(fed, result.get("excluded").toString());
        }

        @Test void puppyRecommendationWarnsThatCalciumPhosphorusIsNotChecked() throws Exception {
            String puppy = "{\"profile\":{\"name\":\"검산\",\"species\":\"DOG\",\"weightKg\":10,\"age\":{\"value\":20,\"unit\":\"WEEK\"},\"adultSize\":\"M\",\"completeFeed\":true},"
                    + "\"items\":[{\"productId\":\"feed-complete-unknown\",\"dailyAmount\":150,\"unit\":\"G\"}],\"standardProfile\":\"NIAS_V3\"}";
            JsonNode warnings = recommend(mvc, mapper, puppy).get("warnings");
            assertEquals(2, warnings.size(), warnings.toString());
            assertEquals(REFERENCE_ONLY, warnings.get(0).asText());
            assertTrue(warnings.get(1).asText().startsWith("칼슘:인 비율 미적용"), warnings.toString());
        }

        @Test void fediafRecommendationHasNoReferenceWarning() throws Exception {
            JsonNode result = recommend(mvc, mapper, ADULT_DOG.replace("\"NIAS_V3\"", "\"FEDIAF_2025\""));
            assertEquals("FEDIAF_2025", result.get("standardProfile").asText());
            assertEquals(0, result.get("warnings").size(), result.get("warnings").toString());
        }
    }
}