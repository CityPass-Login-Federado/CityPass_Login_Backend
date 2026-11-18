package citypass.loginfederado.analytics;

import citypass.loginfederado.config.AnalyticsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsClientContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void springCreatesAnalyticsClientUsingProductionConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AnalyticsClient.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AnalyticsClient.class)
    static class TestConfiguration {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        AnalyticsProperties analyticsProperties() {
            return new AnalyticsProperties(
                    "https://analytics.example.com",
                    "/embed/identidad",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2));
        }
    }
}
