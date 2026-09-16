package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eda")
public record EdaProperties(
        String gatewayUrl,
        String tokenUrl,
        String eventPath,
        String tokenPath,
        String clientId,
        String clientSecret
) {
    public boolean enabled() {
        return gatewayUrl != null && !gatewayUrl.isBlank();
    }
}