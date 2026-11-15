package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class EdaConfigTest {

    @Test
    void configEnablesEdaPropertiesBinding() {
        EnableConfigurationProperties annotation = EdaConfig.class.getAnnotation(EnableConfigurationProperties.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(EdaProperties.class);
    }

    @Test
    void edaPropertiesProvidesDefaultTimeoutsWhenNull() {
        EdaProperties props = new EdaProperties("http://gw", "http://tok", "/oauth/token", "c", "s", null, null);
        assertThat(props.connectTimeoutMillisOrDefault()).isEqualTo(2000L);
        assertThat(props.readTimeoutMillisOrDefault()).isEqualTo(5000L);

        EdaProperties custom = new EdaProperties("http://gw", "http://tok", "/oauth/token", "c", "s", 1500L, 4000L);
        assertThat(custom.connectTimeoutMillisOrDefault()).isEqualTo(1500L);
        assertThat(custom.readTimeoutMillisOrDefault()).isEqualTo(4000L);
    }

    @Test
    void restClientTimeoutCustomizerConfiguresRequestFactory() {
        EdaConfig config = new EdaConfig();
        EdaProperties props = new EdaProperties("http://gw", "http://tok", "/oauth/token", "c", "s", 1000L, 2500L);
        RestClient.Builder builder = RestClient.builder();

        config.edaRestClientTimeouts(props).customize(builder);

        assertThat(builder.build()).isNotNull();
    }
}
