package kr.co.wooaeyoung.standards;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StandardsProperties.class)
public class StandardsConfiguration {
    public StandardsConfiguration(StandardsProperties properties) {}
}
