package kr.co.wooaeyoung.standards;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableConfigurationProperties(StandardsProperties.class)
public class StandardsConfiguration {
    public StandardsConfiguration(StandardsProperties properties, Environment environment) {
        if (properties.allowUnverified() && !environment.acceptsProfiles(Profiles.of("test")))
            throw new IllegalStateException("wooaeyoung.standards.allow-unverified=true는 test 프로필에서만 허용됩니다.");
    }
}
