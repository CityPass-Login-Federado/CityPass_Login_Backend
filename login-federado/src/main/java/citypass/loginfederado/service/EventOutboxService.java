package citypass.loginfederado.service;

import citypass.loginfederado.config.EdaOutboxProperties;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import citypass.loginfederado.model.EventOutbox;
import citypass.loginfederado.model.EventOutboxStatus;
import citypass.loginfederado.repository.EventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventOutboxService {

    private static final int MAX_BATCH = 100;

    private final EventOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public EventOutboxService(EventOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Transacción propia: si el insert falla, no arrastra el logout
     * (que ya revocó el token en la transacción de afuera).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(RawAuthenticationEvent event) {
        if (event == null || event.eventId() == null) {
            throw new IllegalArgumentException("El evento no tiene id");
        }
        try {
            UUID id = UUID.fromString(event.eventId());
            String json = objectMapper.writeValueAsString(event);
            repository.save(new EventOutbox(id, event.eventType(), json, Instant.now()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el evento " + event.eventType(), ex);
        }
    }

    @Transactional
    public List<EventOutbox> claimDue(int batchSize, Duration claimLease) {
        int limit = Math.min(Math.max(batchSize, 1), MAX_BATCH);
        Instant now = Instant.now();
        List<EventOutbox> due = repository.lockDue(now, limit);
        Instant until = now.plus(claimLease);
        for (EventOutbox event : due) {
            event.claimUntil(until);
        }
        repository.saveAll(due);
        return due;
    }

    @Transactional
    public void markSent(UUID id) {
        repository.findById(id).orElseThrow().markSent(Instant.now());
    }

    @Transactional
    public void recordFailure(UUID id, Exception error, EdaOutboxProperties properties, Duration notBefore) {
        String message = error == null ? "error desconocido" : error.toString();
        repository.findById(id).orElseThrow().recordFailure(
                Instant.now(),
                message,
                properties.initialBackoff(),
                properties.maxBackoff(),
                properties.maxAttempts(),
                notBefore
        );
    }

    public long countPending() {
        return repository.countByStatus(EventOutboxStatus.PENDING);
    }
}
