package citypass.loginfederado.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de autenticación esperando salir hacia EDA.
 * El id de la fila es el mismo que viaja en el payload (authEventId):
 * si el relay lo manda dos veces, EDA puede descartar el duplicado.
 */
@Entity
@Table(name = "event_outbox")
public class EventOutbox {

    private static final int MAX_ERROR_LENGTH = 1024;

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    protected EventOutbox() {
    }

    public EventOutbox(UUID id, String eventType, String payload, Instant now) {
        this.id = id;
        this.eventType = eventType;
        this.payload = payload;
        this.status = EventOutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void claimUntil(Instant until) {
        this.nextAttemptAt = until;
    }

    public void markSent(Instant when) {
        this.status = EventOutboxStatus.SENT;
        this.sentAt = when;
        this.lastError = null;
    }

    public void recordFailure(
            Instant now,
            String error,
            Duration initialBackoff,
            Duration maxBackoff,
            int maxAttempts
    ) {
        this.attempts++;
        this.lastError = truncate(error);
        if (this.attempts >= maxAttempts) {
            this.status = EventOutboxStatus.FAILED;
            return;
        }
        this.status = EventOutboxStatus.PENDING;
        this.nextAttemptAt = now.plus(backoff(this.attempts, initialBackoff, maxBackoff));
    }

    static Duration backoff(int attempts, Duration initial, Duration max) {
        Duration delay = initial;
        for (int step = 1; step < attempts; step++) {
            if (delay.compareTo(max) >= 0) {
                return max;
            }
            try {
                Duration doubled = delay.plus(delay);
                if (doubled.isNegative() || doubled.compareTo(max) > 0) {
                    return max;
                }
                delay = doubled;
            } catch (ArithmeticException ex) {
                return max;
            }
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public EventOutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }
}
