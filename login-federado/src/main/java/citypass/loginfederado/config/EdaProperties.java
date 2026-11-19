package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "eda")
public record EdaProperties(
        String gatewayUrl,
        String tokenUrl,
        String tokenPath,
        String clientId,
        String clientSecret,
        Duration connectTimeout,
        Duration readTimeout
) {
    public EdaProperties {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = Duration.ofSeconds(3);
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = Duration.ofSeconds(5);
        }
    }

    public boolean enabled() {
        return gatewayUrl != null && !gatewayUrl.isBlank();
    }
}
