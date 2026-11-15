package citypass.loginfederado.repository;

import citypass.loginfederado.model.EventOutbox;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventOutboxRepositoryCustomImplTest {

    @Test
    void returnsEmptyWhenNothingIsDue() {
        EntityManager entityManager = mock(EntityManager.class);
        Query nativeQuery = mock(Query.class);
        NativeQuery<Object> unwrapped = nativeQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        doReturn(unwrapped).when(nativeQuery).unwrap(NativeQuery.class);
        when(unwrapped.getResultList()).thenReturn(List.of());

        List<EventOutbox> due = new EventOutboxRepositoryCustomImpl(entityManager)
                .lockDue(Instant.parse("2026-11-14T15:00:00Z"), 10);

        assertThat(due).isEmpty();
        verify(entityManager, never()).createQuery(anyString(), eq(EventOutbox.class));
    }

    @Test
    void loadsRowsWhetherTheDriverReturnsUuidOrText() {
        UUID first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        EventOutbox row = new EventOutbox(first, "identidad.login", "{}", Instant.parse("2026-11-14T15:00:00Z"));
        EntityManager entityManager = mock(EntityManager.class);
        Query nativeQuery = mock(Query.class);
        NativeQuery<Object> unwrapped = nativeQuery();
        TypedQuery<EventOutbox> load = typedQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        doReturn(unwrapped).when(nativeQuery).unwrap(NativeQuery.class);
        when(unwrapped.getResultList()).thenReturn(List.of(first, second.toString()));
        when(entityManager.createQuery(anyString(), eq(EventOutbox.class))).thenReturn(load);
        when(load.getResultList()).thenReturn(List.of(row));

        List<EventOutbox> due = new EventOutboxRepositoryCustomImpl(entityManager)
                .lockDue(Instant.parse("2026-11-14T15:00:00Z"), 10);

        assertThat(due).containsExactly(row);
        verify(load).setParameter("ids", List.of(first, second));
    }

    @SuppressWarnings("unchecked")
    private static NativeQuery<Object> nativeQuery() {
        NativeQuery<Object> query = mock(NativeQuery.class);
        doReturn(query).when(query).setParameter(anyString(), any());
        return query;
    }

    @SuppressWarnings("unchecked")
    private static TypedQuery<EventOutbox> typedQuery() {
        TypedQuery<EventOutbox> query = mock(TypedQuery.class);
        doReturn(query).when(query).setParameter(anyString(), any());
        return query;
    }
}
