package citypass.loginfederado.repository;

import citypass.loginfederado.model.EventOutbox;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Toma un lote de pendientes vencidos sin pisarse con otra instancia:
 * la fila que ya está trabada por otro relay se saltea.
 */
public class EventOutboxRepositoryCustomImpl implements EventOutboxRepositoryCustom {

    private final EntityManager entityManager;

    public EventOutboxRepositoryCustomImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<EventOutbox> lockDue(Instant now, int limit) {
        // timestamptz contra un Instant sin tipo usa la zona de la JVM.
        // UTC fijo hace que la fila vencida salga igual en UTC y en Argentina.
        @SuppressWarnings("unchecked")
        NativeQuery<Object> query = entityManager.createNativeQuery("""
                SELECT id
                FROM event_outbox
                WHERE status = 'PENDING'
                  AND next_attempt_at <= :now
                ORDER BY next_attempt_at
                LIMIT CAST(:limit AS integer)
                FOR UPDATE SKIP LOCKED
                """)
                .unwrap(NativeQuery.class);
        List<Object> ids = query
                .setParameter("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .setParameter("limit", limit)
                .getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> typed = new ArrayList<>(ids.size());
        for (Object id : ids) {
            typed.add(id instanceof UUID uuid ? uuid : UUID.fromString(id.toString()));
        }
        return entityManager.createQuery("""
                SELECT event
                FROM EventOutbox event
                WHERE event.id IN :ids
                ORDER BY event.nextAttemptAt
                """, EventOutbox.class)
                .setParameter("ids", typed)
                .getResultList();
    }
}
