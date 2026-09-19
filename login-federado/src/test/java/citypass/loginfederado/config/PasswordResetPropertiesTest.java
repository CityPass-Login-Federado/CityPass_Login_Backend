package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetPropertiesTest {

    @Test
    void blankValuesFallBackToDefaults() {
        PasswordResetProperties properties = new PasswordResetProperties(
                null, false, "  ", 0, -1, 0, 0);

        assertThat(properties.from()).isEqualTo("no-reply@citypass.local");
        assertThat(properties.debugLog()).isFalse();
        assertThat(properties.resetLinkBase()).isEqualTo("http://localhost:3000/reset-password");
        assertThat(properties.tokenTtlMinutes()).isEqualTo(30);
        assertThat(properties.cooldownMinutes()).isEqualTo(5);
        assertThat(properties.maxPerAccountPerHour()).isEqualTo(3);
        assertThat(properties.maxPerIpPerHour()).isEqualTo(50);
    }

    @Test
    void explicitValuesAreKept() {
        PasswordResetProperties properties = new PasswordResetProperties(
                "x@y.z", true, "https://app/reset", 60, 10, 5, 100);

        assertThat(properties.from()).isEqualTo("x@y.z");
        assertThat(properties.debugLog()).isTrue();
        assertThat(properties.resetLinkBase()).isEqualTo("https://app/reset");
        assertThat(properties.tokenTtlMinutes()).isEqualTo(60);
        assertThat(properties.cooldownMinutes()).isEqualTo(10);
        assertThat(properties.maxPerAccountPerHour()).isEqualTo(5);
        assertThat(properties.maxPerIpPerHour()).isEqualTo(100);
    }
}
