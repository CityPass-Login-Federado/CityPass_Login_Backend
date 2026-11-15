package citypass.loginfederado.event;

import citypass.loginfederado.metrics.RawAuthenticationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventOutboxPayloadRoundTripTest {

    private final ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder()
            .modules(new JavaTimeModule())
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final EdaEventPayloadMapper payloadMapper = new EdaEventPayloadMapper(objectMapper);

    @Test
    void storedEventProducesTheSamePayloadAsDirectPublish() throws Exception {
        Instant occurredAt = Instant.parse("2026-11-14T15:04:05.123456789Z");
        RawAuthenticationEvent original = new RawAuthenticationEvent(
                "22222222-2222-2222-2222-222222222222",
                "identidad.refresh",
                occurredAt,
                "U000042",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "10.0.0.8",
                "Mozilla/5.0");

        String stored = objectMapper.writeValueAsString(original);
        RawAuthenticationEvent reread = objectMapper.readValue(stored, RawAuthenticationEvent.class);

        assertThat(reread).isEqualTo(original);
        assertThat(reread.occurredAt()).isEqualTo(occurredAt);
        assertThat(payloadMapper.toPayload(reread)).isEqualTo(payloadMapper.toPayload(original));
        assertThat(payloadMapper.toPayload(reread).get("occurredAt").asLong())
                .isEqualTo(occurredAt.toEpochMilli());
        assertThat(payloadMapper.toPayload(reread).get("authEventId").asText())
                .isEqualTo(original.eventId());
    }

    @Test
    void logoutWithNullFieldsRoundTrips() throws Exception {
        RawAuthenticationEvent original = new RawAuthenticationEvent(
                "33333333-3333-3333-3333-333333333333",
                "identidad.logout",
                Instant.parse("2026-01-01T00:00:00Z"),
                "U000042",
                null,
                "reclamos",
                null,
                null,
                null,
                null);

        RawAuthenticationEvent reread = objectMapper.readValue(
                objectMapper.writeValueAsString(original), RawAuthenticationEvent.class);

        assertThat(reread).isEqualTo(original);
        assertThat(payloadMapper.toPayload(reread)).isEqualTo(payloadMapper.toPayload(original));
    }
}
