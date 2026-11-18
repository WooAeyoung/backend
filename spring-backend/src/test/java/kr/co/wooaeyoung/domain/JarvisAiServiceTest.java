package kr.co.wooaeyoung.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JarvisAiServiceTest {
    @Test void buildsChatUrlFromOriginOrV1Base() {
        assertEquals("https://router.example/v1/chat/completions",
                JarvisAiService.freeLlmApiChatUrl("https://router.example/"));
        assertEquals("https://router.example/v1/chat/completions",
                JarvisAiService.freeLlmApiChatUrl("https://router.example/v1"));
    }

    @Test void validatesRouterAddressAndModel() {
        assertEquals("auto:reliable", JarvisAiService.safeRouterModel("auto:reliable"));
        assertThrows(IllegalArgumentException.class,
                () -> JarvisAiService.freeLlmApiChatUrl("file:///tmp/router"));
        assertThrows(IllegalArgumentException.class,
                () -> JarvisAiService.safeRouterModel("bad model"));
    }
}
