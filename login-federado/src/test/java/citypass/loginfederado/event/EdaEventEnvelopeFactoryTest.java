package citypass.loginfederado.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdaEventEnvelopeFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EdaEventEnvelopeFactory factory = new EdaEventEnvelopeFactory(objectMapper);

    @Test
    void renamesUserSubToActorSubAndUsesPayloadTimestamp() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userSub", "sub-42");
        payload.put("occurredAt", "2024-02-03T04:05:06Z");
        payload.put("sessionId", "sess-99");

        EdaEventEnvelope envelope = factory.create(
                "user.login",
                payload,
                "gateway-service",
                "com.citypass.auth",
                "token-9");

        assertThat(envelope.type()).isEqualTo("user.login");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2024-02-03T04:05:06Z"));
        assertThat(envelope.metadata().source()).isEqualTo("gateway-service");
        assertThat(envelope.metadata().namespace()).isEqualTo("com.citypass.auth");
        assertThat(envelope.metadata().tokenId()).isEqualTo("token-9");

        assertThat(envelope.data().get("actorSub").asText()).isEqualTo("sub-42");
        assertThat(envelope.data().has("userSub")).isFalse();
        assertThat(envelope.data().get("sessionId").asText()).isEqualTo("sess-99");
    }
}
