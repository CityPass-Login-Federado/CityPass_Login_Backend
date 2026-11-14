package citypass.loginfederado.event;

import citypass.loginfederado.config.EdaOutboxProperties;
import citypass.loginfederado.config.EdaProperties;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import citypass.loginfederado.model.EventOutbox;
import citypass.loginfederado.service.EventOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
                    outbox.recordFailure(event.getId(), ex, outboxProperties);
                } catch (RuntimeException updateEx) {
                    log.error("No se pudo registrar el fallo del evento {}", event.getId(), updateEx);
                }
            }
        }
        long pending = outbox.countPending();
        log.info("Outbox EDA: enviados={}, fallidos={}, pendientes={}", sent, failed, pending);
    }
}
