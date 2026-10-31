package kr.co.wooaeyoung;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StandardProfileApiTest {
    @Autowired MockMvc mvc;

    private static String request(String species, double weightKg, String feed, double grams, String standardProfile) {
        return "{\"profile\":{\"name\":\"테스트\",\"species\":\"" + species + "\",\"weightKg\":" + weightKg
                + ",\"age\":{\"value\":36,\"unit\":\"MONTH\"},\"completeFeed\":true},"
                + "\"items\":[{\"productId\":\"" + feed + "\",\"dailyAmount\":" + grams + ",\"unit\":\"G\"}]"
                + (standardProfile == null ? "" : ",\"standardProfile\":\"" + standardProfile + "\"") + "}";
    }

    @Test void standardProfileDefaultsToFediafAndVersionFollowsProfile() throws Exception {
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(request("DOG", 10, "feed-balanced-dog", 150, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardProfile").value("FEDIAF_2025"))
                .andExpect(jsonPath("$.standardVersion").value("FEDIAF-2025.09"))
                .andExpect(jsonPath("$.parameterVersion").value("FEDIAF-2025.09+params.1"));
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(request("DOG", 10, "feed-balanced-dog", 150, "NIAS_V3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardProfile").value("NIAS_V3"))
                .andExpect(jsonPath("$.standardVersion").value("NIAS-v3"))
                .andExpect(jsonPath("$.parameterVersion").value("NIAS-v3+params.2026-09-27"))
                .andExpect(jsonPath("$.parameterVerification.energy").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.warnings", hasItem(startsWith("매개변수 미검증"))))
                .andExpect(jsonPath("$.warnings", hasItem("총 오메가3 값은 EPA+DHA로 환산하지 않아 NIAS 분석에서 제외했습니다.")));
    }

    @Test void invalidStandardProfileUsesExistingBadRequestFormat() throws Exception {
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(request("DOG", 10, "feed-balanced-dog", 150, "NOPE")))
                .andExpect(status().isBadRequest());
    }

    @Test void v2StandardAndParameterCatalogsExposeProfileVersions() throws Exception {
        mvc.perform(get("/api/v2/standards/NIAS_V3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardVersion").value("NIAS-v3"))
                .andExpect(jsonPath("$.items", hasSize(44)))
                .andExpect(jsonPath("$.items[0].implemented").exists());
        mvc.perform(get("/api/v2/parameters/NIAS_V3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterVersion").value("NIAS-v3+params.2026-09-27"))
                .andExpect(jsonPath("$.verification.energy").value("PENDING_VERIFICATION"));
    }

    @Test void calciumPhosphorusIsNotApplicableToCatsInBothProfiles() throws Exception {
        for (String profile : new String[] {"FEDIAF_2025", "NIAS_V3"}) {
            mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(request("CAT", 4, "feed-balanced-cat", 60, profile)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.standardProfile").value(profile))
                    .andExpect(jsonPath("$.ratioApplicability.calciumPhosphorus").value("NOT_APPLICABLE"))
                    .andExpect(jsonPath("$.ratios.calciumPhosphorus").doesNotExist());
        }
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(request("DOG", 10, "feed-balanced-dog", 150, null)))
                .andExpect(jsonPath("$.ratioApplicability.calciumPhosphorus").value("APPLICABLE"));
    }

    @Test void niasRecommendationReturnsReferenceResultsWithWarning() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(request("DOG", 10, "feed-balanced-dog", 150, "NIAS_V3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardProfile").value("NIAS_V3"))
                .andExpect(jsonPath("$.standardVersion").value("NIAS-v3"))
                .andExpect(jsonPath("$.items", not(empty())))
                .andExpect(jsonPath("$.warnings", not(empty())))
                .andExpect(jsonPath("$.warnings[0]", containsString("검증되지 않은 참고 정보")));
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(request("DOG", 10, "feed-balanced-dog", 150, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardProfile").value("FEDIAF_2025"))
                .andExpect(jsonPath("$.standardVersion").value("FEDIAF-2025.09"))
                .andExpect(jsonPath("$.items", not(empty())));
    }

    @Test void kakaoStatusAndHelpTextsAreUnchanged() throws Exception {
        mvc.perform(post("/api/v1/kakao/skill").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userRequest\":{\"utterance\":\"/상태\",\"user\":{\"id\":\"guest\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.outputs[0].simpleText.text").value("우애영 봇과 Java Spring Backend가 정상적으로 연결되어 있습니다."));
        mvc.perform(post("/api/v1/kakao/skill").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userRequest\":{\"utterance\":\"/도움말\",\"user\":{\"id\":\"guest\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.outputs[0].simpleText.text").value("사용 가능한 명령어\n• /상태 - 연결 상태 확인\n• /이슈 - GitHub 이슈 명령 안내\n• /배포 - GitHub Actions 실행 안내\n• /로그인 - 카카오 인증 시작\n• /백엔드 - Backend 주소 보기\n• /프론트 - 프론트엔드 주소 보기\n• /내아이디 - 명령 권한 등록용 ID 확인"))
                .andExpect(jsonPath("$.template.quickReplies", hasSize(8)));
        mvc.perform(post("/api/v1/kakao/skill").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userRequest\":{\"utterance\":\"/이슈 backend 테스트\",\"user\":{\"id\":\"guest\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.outputs[0].simpleText.text").value("실행 권한이 없습니다. /내아이디를 보내고 표시된 ID를 관리자에게 등록해 주세요."));
    }
}
