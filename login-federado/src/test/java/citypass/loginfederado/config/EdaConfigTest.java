package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThat;

class EdaConfigTest {

    @Test
    void configEnablesEdaPropertiesBinding() {
        EnableConfigurationProperties annotation = EdaConfig.class.getAnnotation(EnableConfigurationProperties.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(EdaProperties.class);
    }
}
