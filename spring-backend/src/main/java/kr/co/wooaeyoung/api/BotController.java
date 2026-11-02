package kr.co.wooaeyoung.api;

import kr.co.wooaeyoung.integration.KakaoBotService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/kakao")
public class BotController {
    private final KakaoBotService bot;
    public BotController(KakaoBotService bot) { this.bot = bot; }

    @PostMapping("/skill")
    public Map<String, Object> skill(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Wooaeyoung-Skill-Secret", required = false) String secret) {
        return bot.respond(payload, secret);
    }
}
