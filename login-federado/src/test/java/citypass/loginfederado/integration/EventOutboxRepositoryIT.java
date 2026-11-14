package citypass.loginfederado.integration;

import citypass.loginfederado.event.EdaEventPayloadMapper;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import citypass.loginfederado.model.EventOutbox;
import citypass.loginfederado.model.EventOutboxStatus;
import citypass.loginfederado.repository.EventOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always"
})
class EventOutboxRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("login_federado")
            .withUsername("citypass")
            .withPassword("citypass");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder()
            .modules(new JavaTimeModule())
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Autowired
    EventOutboxRepository outbox;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void cleanOutbox() {
        // El test de SKIP LOCKED commitea de verdad. Si corre primero, deja
        // una fila vencida que el otro test interpretaría como propia.
        outbox.deleteAll();
    }

    @Test
    void locksOnlyDuePendingRowsAndKeepsThePayload() throws Exception {
        Instant now = Instant.now();
        Instant occurredAt = Instant.parse("2026-11-14T15:04:05.123456789Z");
        RawAuthenticationEvent event = new RawAuthenticationEvent(
                "44444444-4444-4444-4444-444444444444",
                "identidad.login",
                occurredAt,
                "U000042",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                null,
                "10.0.0.1",
                "JUnit");
        EventOutbox due = new EventOutbox(
                UUID.fromString(event.eventId()),
                event.eventType(),
                objectMapper.writeValueAsString(event),
                now.minusSeconds(30));
        EventOutbox later = new EventOutbox(
                UUID.randomUUID(), "identidad.refresh", "{\"eventId\":\"x\"}", now.plusSeconds(3600));
        EventOutbox sent = new EventOutbox(
                UUID.randomUUID(), "identidad.logout", "{\"eventId\":\"y\"}", now.minusSeconds(30));
        sent.markSent(now);
        outbox.saveAndFlush(due);
        outbox.saveAndFlush(later);
        outbox.saveAndFlush(sent);
        entityManager.clear();

        List<EventOutbox> locked = outbox.lockDue(now, 10);

        assertThat(locked).extracting(EventOutbox::getId).containsExactly(due.getId());
        RawAuthenticationEvent reread = objectMapper.readValue(locked.get(0).getPayload(), RawAuthenticationEvent.class);
        EdaEventPayloadMapper mapper = new EdaEventPayloadMapper(objectMapper);
        assertThat(mapper.toPayload(reread)).isEqualTo(mapper.toPayload(event));
        assertThat(reread.occurredAt().toEpochMilli()).isEqualTo(occurredAt.toEpochMilli());
        assertThat(reread.eventId()).isEqualTo(due.getId().toString());
    }

    @Test
    @Timeout(20)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void skipLockedDoesNotReturnARowHeldByAnotherTransaction() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> outbox.saveAndFlush(
                new EventOutbox(id, "identidad.login", "{\"eventId\":\"z\"}", Instant.now().minusSeconds(5))));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    List<EventOutbox> held = outbox.lockDue(Instant.now(), 10);
                    assertThat(held).extracting(EventOutbox::getId).contains(id);
                    locked.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                });
            } catch (Throwable ex) {
                holderError.set(ex);
                locked.countDown();
            }
        });
        holder.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(holderError.get()).isNull();

        List<EventOutbox> skipped = tx.execute(status -> {
            entityManager.unwrap(Session.class).doWork(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '2s'");
                }
            });
            return outbox.lockDue(Instant.now(), 10);
        });
        assertThat(skipped).extracting(EventOutbox::getId).doesNotContain(id);

        release.countDown();
        holder.join(5_000);
        assertThat(holderError.get()).isNull();
    }

    @Test
    void promotesAnExistingTimestampColumnToTimestamptz() throws Exception {
        entityManager.createNativeQuery("""
                ALTER TABLE event_outbox
                    ALTER COLUMN next_attempt_at TYPE timestamp USING next_attempt_at AT TIME ZONE 'UTC',
                    ALTER COLUMN created_at TYPE timestamp USING created_at AT TIME ZONE 'UTC',
                    ALTER COLUMN sent_at TYPE timestamp USING sent_at AT TIME ZONE 'UTC'
                """).executeUpdate();
        UUID id = UUID.fromString("55555555-5555-5555-5555-555555555555");
        entityManager.createNativeQuery("""
                INSERT INTO event_outbox
                    (id, event_type, payload, status, attempts, next_attempt_at, created_at)
                VALUES
                    (:id, 'identidad.login', CAST(:payload AS jsonb), 'PENDING', 0,
                     make_timestamp(2020, 1, 1, 0, 0, 0), make_timestamp(2020, 1, 1, 0, 0, 0))
                """)
                .setParameter("id", id)
                .setParameter("payload", "{\"eventId\":\"" + id + "\"}")
                .executeUpdate();

        entityManager.unwrap(Session.class).doWork(connection -> ScriptUtils.executeSqlScript(
                connection, new ByteArrayResource(migrationBlock().getBytes(StandardCharsets.UTF_8))));
        entityManager.flush();
        entityManager.clear();

        String dataType = entityManager.createNativeQuery("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'event_outbox'
                  AND column_name = 'next_attempt_at'
                """).getSingleResult().toString();
        assertThat(dataType).isEqualTo("timestamp with time zone");
        assertThat(outbox.lockDue(Instant.parse("2020-01-01T00:00:01Z"), 10))
                .extracting(EventOutbox::getId)
                .contains(id);
        assertThat(outbox.lockDue(Instant.parse("2019-12-31T23:59:00Z"), 10))
                .extracting(EventOutbox::getId)
                .doesNotContain(id);
    }

    private static String migrationBlock() {
        try (var in = EventOutboxRepositoryIT.class.getClassLoader().getResourceAsStream("schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("CREATE OR REPLACE FUNCTION event_outbox_promote_timestamps");
            int end = sql.indexOf("DROP FUNCTION event_outbox_promote_timestamps();", start);
            return sql.substring(start, end + "DROP FUNCTION event_outbox_promote_timestamps();".length());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
