package citypass.loginfederado.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EdaEventEnvelopeFactory {
    private final ObjectMapper objectMapper;

    public EdaEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EdaEventEnvelope create(String eventType, Object payload,
                                   String source, String namespace, String tokenId) {
        JsonNode data = objectMapper.valueToTree(payload);
        if (data instanceof ObjectNode object && object.has("userSub")) {
            object.set("actorSub", object.remove("userSub"));
        }
        Instant occurredAt = data.hasNonNull("occurredAt")
                ? Instant.parse(data.get("occurredAt").asText()) : Instant.now();
        return new EdaEventEnvelope(
                eventType, 1, occurredAt,
                new EdaEventEnvelope.Metadata(source, namespace, tokenId), data);
    }
}