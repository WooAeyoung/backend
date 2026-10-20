package kr.co.wooaeyoung;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiCompatibilityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final String REQUEST = """
        {"profile":{"name":"몽이","species":"DOG","weightKg":6.2,"age":{"value":24,"unit":"MONTH"},"completeFeed":true},
         "items":[{"productId":"feed-balanced-dog","dailyAmount":80,"unit":"G"}],"manualItems":[]}
        """;

    @Test void healthAndProductsMatchContract() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.standardVersion").value("FEDIAF-2025.09"));
        // Unicode escape keeps this HTTP-query contract portable across Windows code pages.
        mvc.perform(get("/api/v1/products").param("query", "\uCE7C\uC298"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("supp-calcium"));
    }

    @Test void analysisMatchesFrontendContract() throws Exception {
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardVersion").value("FEDIAF-2025.09"))
                .andExpect(jsonPath("$.nutrients.length()").value(6))
                .andExpect(jsonPath("$.ratios.calciumPhosphorus.status").value("ADEQUATE"));
    }

    @Test void recommendationAndValidationMatchContract() throws Exception {
        String recommendation = REQUEST.substring(0, REQUEST.lastIndexOf('}')) + ",\"maxItems\":2}";
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(recommendation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        String invalid = REQUEST.replace("24", "1");
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail.code").value("CALCULATION_INPUT_ERROR"));
    }

    @Test void kakaoSkillSupportsStatusAndProtectsMutations() throws Exception {
        String statusPayload = """
            {"userRequest":{"utterance":"/상태","user":{"id":"guest"}}}
            """;
        mvc.perform(post("/api/v1/kakao/skill").contentType(MediaType.APPLICATION_JSON).content(statusPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2.0"))
                .andExpect(jsonPath("$.template.outputs[0].simpleText.text").value(org.hamcrest.Matchers.containsString("Java Spring Backend")));
        String issuePayload = """
            {"userRequest":{"utterance":"/이슈 backend 테스트","user":{"id":"guest"}}}
            """;
        mvc.perform(post("/api/v1/kakao/skill").contentType(MediaType.APPLICATION_JSON).content(issuePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.outputs[0].simpleText.text").value(org.hamcrest.Matchers.containsString("실행 권한이 없습니다")));
    }
}
