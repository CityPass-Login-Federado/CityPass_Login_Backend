package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EdaOutboxPropertiesTest {

    @Test
    void usesTheDefaultsWhenTheValuesAreMissing() {
        EdaOutboxProperties properties = new EdaOutboxProperties(0, 0, 0, null, null, null);

        assertThat(properties.pollIntervalMs()).isEqualTo(5_000);
        assertThat(properties.batchSize()).isEqualTo(20);
        assertThat(properties.maxAttempts()).isEqualTo(20);
        assertThat(properties.initialBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.maxBackoff()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.claimLease()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void usesTheDefaultsWhenTheValuesAreNegative() {
        Duration negative = Duration.ofSeconds(-1);
        EdaOutboxProperties properties = new EdaOutboxProperties(-1, -1, -1, negative, negative, negative);

        assertThat(properties.maxAttempts()).isEqualTo(20);
        assertThat(properties.initialBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.claimLease()).isEqualTo(Duration.ofSeconds(30));
    }
}
