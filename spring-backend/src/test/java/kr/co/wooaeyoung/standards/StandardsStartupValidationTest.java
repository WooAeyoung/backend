package kr.co.wooaeyoung.standards;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 기준 설정이 잘못되면 애플리케이션 컨텍스트 로딩이 실패해야 한다(BE-V3-004). */
class StandardsStartupValidationTest {

    @Configuration
    static class ParametersAt {
        @Bean FediafParameters fediafParameters(StandardParametersLoader loader, @Value("${test.parameters}") String location) {
            return loader.load(location, FediafParameters.class);
        }
    }

    private final ApplicationContextRunner parameters = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(StandardParametersLoader.class, ParametersAt.class);

    private final ApplicationContextRunner guard = new ApplicationContextRunner()
            .withUserConfiguration(StandardsConfiguration.class);

    @Test void validParametersLoad() {
        parameters.withPropertyValues("test.parameters=standards/fediaf-2025/parameters.json")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(FediafParameters.class));
    }

    @Test void missingFieldFailsContext() {
        assertFails("standards-invalid/fediaf-parameters-missing-field.json", "energy.dogAdultK");
    }

    @Test void outOfRangeValueFailsContext() {
        assertFails("standards-invalid/fediaf-parameters-out-of-range.json", "cautionRatio.ratio.dog");
    }

    @Test void nonZeroFediafSafetyMarginWeightFailsContext() {
        assertFails("standards-invalid/fediaf-parameters-w3-nonzero.json", "recommendation.w3");
    }

    @Test void unknownFieldFailsContext() {
        assertFails("standards-invalid/fediaf-parameters-unknown-field.json", "unexpected");
    }

    @Test void allowUnverifiedIsOffByDefault() {
        guard.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StandardsProperties.class).allowUnverified()).isFalse();
        });
    }

    @Test void allowUnverifiedCanBeExplicitlyEnabledOutsideTestProfile() {
        guard.withPropertyValues("wooaeyoung.standards.allow-unverified=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StandardsProperties.class).allowUnverified()).isTrue();
        });
        guard.withPropertyValues("wooaeyoung.standards.allow-unverified=true", "spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StandardsProperties.class).allowUnverified()).isTrue();
        });
    }

    @Test void allowUnverifiedInTestProfileStarts() {
        guard.withPropertyValues("wooaeyoung.standards.allow-unverified=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StandardsProperties.class).allowUnverified()).isTrue();
                });
    }

    private void assertFails(String location, String expectedMessage) {
        parameters.withPropertyValues("test.parameters=" + location).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(expectedMessage);
        });
    }
}
