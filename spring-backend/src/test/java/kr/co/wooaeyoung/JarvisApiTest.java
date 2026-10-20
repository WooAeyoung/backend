package kr.co.wooaeyoung;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class JarvisApiTest {
    @Autowired MockMvc mvc;

    @Test void routesRecordCommand() throws Exception {
        mvc.perform(post("/api/v1/jarvis").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"지난 기록 보여줘\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("RECORDS")).andExpect(jsonPath("$.page").value("records"));
    }

    @Test void summarizesOnlySubmittedAnalysis() throws Exception {
        String body = "{\"message\":\"오늘 분석 결과 요약해 줘\",\"analysis\":{\"summary\":{\"adequate\":3,\"deficient\":1,\"caution\":0,\"excess\":0},\"nutrients\":[{\"nutrientId\":\"CALCIUM\",\"name\":\"칼슘\",\"status\":\"DEFICIENT\"}]}}";
        mvc.perform(post("/api/v1/jarvis").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("ANALYSIS_SUMMARY"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("칼슘 부족")));
    }
}
