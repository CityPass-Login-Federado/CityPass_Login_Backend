package citypass.loginfederado.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import citypass.loginfederado.config.EdaOutboxProperties;
import citypass.loginfederado.config.EdaProperties;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import citypass.loginfederado.model.EventOutbox;
import citypass.loginfederado.model.EventOutboxStatus;
import citypass.loginfederado.repository.EventOutboxRepository;
import citypass.loginfederado.service.EventOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventOutboxRelayTest {

    private final ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder()
            .modules(new JavaTimeModule())
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final EdaProperties edaProperties = new EdaProperties(
            "http://eda", "http://eda", "/oauth/token", "grupo2", "secret",
            Duration.ofSeconds(3), Duration.ofSeconds(5));
    private final EdaOutboxProperties outboxProperties = new EdaOutboxProperties(
            5_000, 20, 20, Duration.ofSeconds(5), Duration.ofMinutes(30), Duration.ofSeconds(30));

    @Test
    void marksSentWhenPublishSucceeds() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventPublisher publisher = mock(EventPublisher.class);
        EventOutboxRelay relay = relay(row, publisher);

        relay.relayDueEvents();

        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        verify(publisher).publish("identidad.login", event);
    }

    @Test
    void schedulesRetryWhenPublishFails() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new IllegalStateException("EDA caido")).when(publisher).publish(any(), any());
        EventOutboxRelay relay = relay(row, publisher);
        Instant before = Instant.now();

        relay.relayDueEvents();

        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("EDA caido");
        assertThat(row.getNextAttemptAt()).isAfter(before.plusSeconds(4));
        assertThat(row.getSentAt()).isNull();
    }

    @Test
    void waitsAtLeastRetryAfterOnTooManyRequests() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(httpError(429, "120")).when(publisher).publish(any(), any());
        EventOutboxRelay relay = relay(row, publisher);
        Instant before = Instant.now();

        relay.relayDueEvents();

        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(before.plusSeconds(110));
    }

    @Test
    void waitsAtLeastAnHttpDateRetryAfter() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        ZonedDateTime when = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(when);
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(httpError(429, header)).when(publisher).publish(any(), any());
        EventOutboxRelay relay = relay(row, publisher);

        relay.relayDueEvents();

        assertThat(row.getNextAttemptAt()).isAfter(when.toInstant().minusSeconds(2));
    }

    @Test
    void retriesNormallyWhenRetryAfterIsMissingOrUnusable() throws Exception {
        assertThat(nextDelayAfter(httpError(429, "   "))).isBetween(4L, 30L);
        assertThat(nextDelayAfter(httpError(429, "manana"))).isBetween(4L, 30L);
        assertThat(nextDelayAfter(httpError(429, "999999999999999999999"))).isBetween(4L, 30L);
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        assertThat(nextDelayAfter(httpError(429, past))).isBetween(4L, 30L);
    }

    @Test
    void retriesAGatewayTimeoutWithTheNormalBackoff() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(httpError(504, "600")).when(publisher).publish(any(), any());
        EventOutboxRelay relay = relay(row, publisher);
        Instant before = Instant.now();

        relay.relayDueEvents();

        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(before.plusSeconds(4));
        assertThat(row.getNextAttemptAt()).isBefore(before.plusSeconds(30));
    }

    @Test
    void marksFailedWhenAttemptsReachTheMaximum() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        Instant now = Instant.now();
        for (int i = 0; i < 19; i++) {
            row.recordFailure(now, "previo", Duration.ofSeconds(5), Duration.ofMinutes(30), 20);
        }
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(new IllegalStateException("sigue caido")).when(publisher).publish(any(), any());
        EventOutboxRelay relay = relay(row, publisher);

        relay.relayDueEvents();

        assertThat(row.getAttempts()).isEqualTo(20);
        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.FAILED);
        assertThat(row.getLastError()).contains("sigue caido");
    }

    @Test
    void loggingPublisherStillMarksTheRowSent() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventPublisher publisher = new LoggingEventPublisher(
                objectMapper, new EdaEventEnvelopeFactory(objectMapper));
        EventOutboxRelay relay = relay(row, publisher);
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingEventPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            relay.relayDueEvents();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.SENT);
        assertThat(appender.list).anyMatch(entry ->
                entry.getLevel() == Level.INFO
                        && entry.getFormattedMessage().contains("EVENTO PUBLICADO")
                        && entry.getFormattedMessage().contains("identidad.login"));
        assertThat(appender.list).noneMatch(entry -> entry.getLevel() == Level.ERROR);
    }

    @Test
    void logsOnlyAtDebugWhenNothingIsDue() {
        EventOutboxRepository repository = mock(EventOutboxRepository.class);
        when(repository.lockDue(any(), anyInt())).thenReturn(List.of());
        EventOutboxRelay relay = new EventOutboxRelay(
                new EventOutboxService(repository, objectMapper),
                mock(EventPublisher.class),
                edaProperties,
                outboxProperties,
                objectMapper);

        Logger logger = (Logger) LoggerFactory.getLogger(EventOutboxRelay.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            relay.relayDueEvents();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertThat(appender.list).anyMatch(entry ->
                entry.getLevel() == Level.DEBUG
                        && entry.getFormattedMessage().contains("no hay eventos vencidos"));
        assertThat(appender.list).noneMatch(entry -> entry.getLevel() == Level.INFO);
    }

    @Test
    void logsCountsWhenItProcessedSomething() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventOutboxRelay relay = relay(row, mock(EventPublisher.class));

        Logger logger = (Logger) LoggerFactory.getLogger(EventOutboxRelay.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            relay.relayDueEvents();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anyMatch(entry ->
                entry.getLevel() == Level.INFO
                        && entry.getFormattedMessage().contains("enviados=1")
                        && entry.getFormattedMessage().contains("fallidos=0")
                        && entry.getFormattedMessage().contains("pendientes="));
    }

    @Test
    void refusesAClaimLeaseShorterThanTheHttpTimeouts() {
        EdaOutboxProperties tooShort = new EdaOutboxProperties(
                5_000, 20, 20, Duration.ofSeconds(5), Duration.ofMinutes(30), Duration.ofSeconds(10));

        assertThatThrownBy(() -> new EventOutboxRelay(
                mock(EventOutboxService.class),
                mock(EventPublisher.class),
                edaProperties,
                tooShort,
                objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim-lease");
    }

    @Test
    void enqueueKeepsTheEventIdAndTheDirectPublishPayload() throws Exception {
        RawAuthenticationEvent event = loginEvent();
        EventOutboxRepository repository = mock(EventOutboxRepository.class);
        new EventOutboxService(repository, objectMapper).enqueue(event);

        ArgumentCaptor<EventOutbox> saved = ArgumentCaptor.forClass(EventOutbox.class);
        verify(repository).save(saved.capture());
        EventOutbox row = saved.getValue();
        assertThat(row.getId()).hasToString(event.eventId());
        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        RawAuthenticationEvent reread = objectMapper.readValue(row.getPayload(), RawAuthenticationEvent.class);
        EdaEventPayloadMapper mapper = new EdaEventPayloadMapper(objectMapper);
        assertThat(mapper.toPayload(reread)).isEqualTo(mapper.toPayload(event));
    }

    @Test
    void truncatesAVeryLongDeliveryError() {
        EventOutbox row = new EventOutbox(UUID.randomUUID(), "identidad.login", "{}", Instant.now());
        row.recordFailure(Instant.now(), "x".repeat(2_000), Duration.ofSeconds(5), Duration.ofMinutes(30), 20);
        assertThat(row.getLastError()).hasSize(1024);
        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
    }

    @Test
    void keepsGoingWhenTheOutboxCannotBeReadOrUpdated() throws Exception {
        EventOutboxService outbox = mock(EventOutboxService.class);
        when(outbox.claimDue(anyInt(), any())).thenThrow(new IllegalStateException("db caida"));
        EventPublisher publisher = mock(EventPublisher.class);
        new EventOutboxRelay(outbox, publisher, edaProperties, outboxProperties, objectMapper).relayDueEvents();
        verify(publisher, never()).publish(any(), any());

        RawAuthenticationEvent event = loginEvent();
        EventOutbox row = rowFor(event);
        EventOutboxRepository repository = mock(EventOutboxRepository.class);
        when(repository.lockDue(any(), anyInt())).thenReturn(List.of(row));
        when(repository.findById(row.getId())).thenThrow(new IllegalStateException("no pude anotar el fallo"));
        EventPublisher failing = mock(EventPublisher.class);
        doThrow(new IllegalStateException("EDA caido")).when(failing).publish(any(), any());
        new EventOutboxRelay(
                new EventOutboxService(repository, objectMapper),
                failing, edaProperties, outboxProperties, objectMapper).relayDueEvents();
        assertThat(row.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void doesNotPublishWhenTheQueueIsEmpty() {
        EventOutboxRepository repository = mock(EventOutboxRepository.class);
        when(repository.lockDue(any(), anyInt())).thenReturn(List.of());
        EventPublisher publisher = mock(EventPublisher.class);
        EventOutboxRelay relay = new EventOutboxRelay(
                new EventOutboxService(repository, objectMapper),
                publisher,
                edaProperties,
                outboxProperties,
                objectMapper);

        relay.relayDueEvents();

        verify(publisher, never()).publish(any(), any());
    }

    private EventOutboxRelay relay(EventOutbox row, EventPublisher publisher) {
        EventOutboxRepository repository = mock(EventOutboxRepository.class);
        when(repository.lockDue(any(), anyInt())).thenReturn(List.of(row));
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        when(repository.countByStatus(EventOutboxStatus.PENDING)).thenReturn(0L);
        return new EventOutboxRelay(
                new EventOutboxService(repository, objectMapper),
                publisher,
                edaProperties,
                outboxProperties,
                objectMapper);
    }

    private EventOutbox rowFor(RawAuthenticationEvent event) throws Exception {
        return new EventOutbox(
                UUID.fromString(event.eventId()),
                event.eventType(),
                objectMapper.writeValueAsString(event),
                Instant.now().minusSeconds(5));
    }

    private long nextDelayAfter(Exception error) throws Exception {
        EventOutbox row = rowFor(loginEvent());
        EventPublisher publisher = mock(EventPublisher.class);
        doThrow(error).when(publisher).publish(any(), any());
        Instant before = Instant.now();
        relay(row, publisher).relayDueEvents();
        return Duration.between(before, row.getNextAttemptAt()).toSeconds();
    }

    private static RestClientResponseException httpError(int status, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return new RestClientResponseException(
                "eda", status, "error", headers, new byte[0], StandardCharsets.UTF_8);
    }

    private RawAuthenticationEvent loginEvent() {
        return new RawAuthenticationEvent(
                UUID.randomUUID().toString(),
                "identidad.login",
                Instant.parse("2026-11-14T15:04:05.123456789Z"),
                "U000042",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                null,
                "10.0.0.8",
                "JUnit");
    }
}
