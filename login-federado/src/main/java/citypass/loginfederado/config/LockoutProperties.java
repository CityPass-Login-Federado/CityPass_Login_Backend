package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.lockout")
public record LockoutProperties(
        int maxFailedAttempts,
        long windowMinutes
) {
}