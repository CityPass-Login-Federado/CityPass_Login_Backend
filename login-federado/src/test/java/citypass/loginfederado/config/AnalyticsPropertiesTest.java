package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AnalyticsConfig.class);

    @Test
    void appliesSafeDefaultsAndNormalizesBaseUrl() {
        AnalyticsProperties properties = new AnalyticsProperties(
                "https://analytics.example.com///", null, null, null);

        assertThat(properties.baseUrl()).isEqualTo("https://analytics.example.com");
        assertThat(properties.embedPath()).isEqualTo("/embed/identidad");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void rejectsNonHttpsBaseUrl() {
        assertThatThrownBy(() -> new AnalyticsProperties(
                "http://analytics.example.com", "/embed/identidad", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsAbsoluteOrParameterizedEmbedUrl() {
        assertThatThrownBy(() -> new AnalyticsProperties(
                "https://analytics.example.com", "https://evil.example.com/embed", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalyticsProperties(
                "https://analytics.example.com", "/embed/identidad?token=secret", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBaseUrlWithCredentialsQueryOrFragment() {
        assertThatThrownBy(() -> new AnalyticsProperties(
                "https://user:secret@analytics.example.com", "/embed/identidad", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalyticsProperties(
                "https://analytics.example.com?tenant=1", "/embed/identidad", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalyticsProperties(
                "https://analytics.example.com#fragment", "/embed/identidad", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeEmbedPaths() {
        for (String path : new String[]{"relative/path", "//evil.example.com/embed",
                "/embed/../private", "/embed\\identidad", "/embed path"}) {
            assertThatThrownBy(() -> new AnalyticsProperties(
                    "https://analytics.example.com", path, null, null))
                    .as("path %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void replacesNonPositiveTimeoutsWithSafeDefaults() {
        AnalyticsProperties properties = new AnalyticsProperties(
                "https://analytics.example.com", "/embed/identidad",
                Duration.ZERO, Duration.ofSeconds(-1));

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void loadsProductionDefaultsFromApplicationYaml() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AnalyticsProperties properties = context.getBean(AnalyticsProperties.class);
            assertThat(properties.baseUrl()).isEqualTo(
                    "https://citypass-backend-prod-becqetepcjbqa8c7.brazilsouth-01.azurewebsites.net");
            assertThat(properties.embedPath()).isEqualTo("/embed/identidad");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
        });
    }

    @Test
    void bindsExplicitSpringProperties() {
        contextRunner
                .withPropertyValues(
                        "analytics.base-url=https://analytics.example.com/root/",
                        "analytics.embed-path=/embed/identidad",
                        "analytics.connect-timeout=4s",
                        "analytics.read-timeout=20s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AnalyticsProperties properties = context.getBean(AnalyticsProperties.class);
                    assertThat(properties.baseUrl()).isEqualTo("https://analytics.example.com/root");
                    assertThat(properties.embedPath()).isEqualTo("/embed/identidad");
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(20));
                });
    }
}
