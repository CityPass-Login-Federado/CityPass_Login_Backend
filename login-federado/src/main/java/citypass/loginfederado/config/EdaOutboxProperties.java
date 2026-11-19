package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "eda.outbox")
public record EdaOutboxProperties(
        long pollIntervalMs,
        int batchSize,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration claimLease
) {
    public EdaOutboxProperties {
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 5_000;
        }
        if (batchSize <= 0) {
            batchSize = 20;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 20;
        }
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            initialBackoff = Duration.ofSeconds(5);
        }
        if (maxBackoff == null || maxBackoff.isZero() || maxBackoff.isNegative()) {
            maxBackoff = Duration.ofMinutes(30);
        }
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            claimLease = Duration.ofSeconds(30);
        }
    }
}
