package citypass.loginfederado.event;

import citypass.loginfederado.config.EdaOutboxProperties;
import citypass.loginfederado.config.EdaProperties;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import citypass.loginfederado.model.EventOutbox;
import citypass.loginfederado.service.EventOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class EventOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(EventOutboxRelay.class);

    private final EventOutboxService outbox;
    private final EventPublisher publisher;
    private final EdaOutboxProperties outboxProperties;
    private final ObjectMapper objectMapper;

    public EventOutboxRelay(
            EventOutboxService outbox,
            EventPublisher publisher,
            EdaProperties edaProperties,
            EdaOutboxProperties outboxProperties,
            ObjectMapper objectMapper
    ) {
        Duration oneCall = edaProperties.connectTimeout().plus(edaProperties.readTimeout());
        Duration minimumLease = oneCall.multipliedBy(2);
        if (outboxProperties.claimLease().compareTo(minimumLease) <= 0) {
            throw new IllegalStateException(
                    "eda.outbox.claim-lease (" + outboxProperties.claimLease()
                            + ") tiene que ser mayor que dos veces connect-timeout + read-timeout ("
                            + minimumLease + ")");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${eda.outbox.poll-interval-ms:5000}")
    public void relayDueEvents() {
        List<EventOutbox> claimed;
        try {
            claimed = outbox.claimDue(outboxProperties.batchSize(), outboxProperties.claimLease());
        } catch (RuntimeException ex) {
            log.error("No se pudo leer el outbox de EDA", ex);
            return;
        }
        if (claimed.isEmpty()) {
            log.debug("Outbox EDA: no hay eventos vencidos");
            return;
        }
        int sent = 0;
        int failed = 0;
        for (EventOutbox event : claimed) {
            try {
                RawAuthenticationEvent payload = objectMapper.readValue(
                        event.getPayload(), RawAuthenticationEvent.class);
                publisher.publish(event.getEventType(), payload);
                outbox.markSent(event.getId());
                sent++;
            } catch (Exception ex) {
                failed++;
                try {
                    outbox.recordFailure(event.getId(), ex, outboxProperties, retryAfter(ex));
                } catch (RuntimeException updateEx) {
                    log.error("No se pudo registrar el fallo del evento {}", event.getId(), updateEx);
                }
            }
        }
        long pending = outbox.countPending();
        log.info("Outbox EDA: enviados={}, fallidos={}, pendientes={}", sent, failed, pending);
    }

    /**
     * Un 429 espera al menos lo que dice Retry-After. Un 504 (y el resto)
     * sigue con la espera que se duplica: el authEventId no cambia y EDA
     * descarta el duplicado por payloadHash.
     */
    static Duration retryAfter(Exception ex) {
        if (!(ex instanceof RestClientResponseException response)) {
            return null;
        }
        if (response.getStatusCode().value() != 429) {
            return null;
        }
        HttpHeaders headers = response.getResponseHeaders();
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            Instant when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(Instant.now(), when);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
