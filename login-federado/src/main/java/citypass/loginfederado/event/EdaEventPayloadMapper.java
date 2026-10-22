
package citypass.loginfederado.event;

import citypass.loginfederado.metrics.RawAuthenticationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class EdaEventPayloadMapper {

    private final ObjectMapper objectMapper;

    public EdaEventPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode toPayload(RawAuthenticationEvent event) {

        Objects.requireNonNull(event, "Event cannot be null");
        Objects.requireNonNull(event.eventId(), "Event ID cannot be null");
        Objects.requireNonNull(event.userSub(), "User sub cannot be null");
        Objects.requireNonNull(event.occurredAt(), "Event timestamp cannot be null");

        ObjectNode data = objectMapper.createObjectNode();

        // Required fields shared by all authentication events.
        data.put("authEventId", event.eventId());
        data.put("actorSub", event.userSub());
        data.put("occurredAt", event.occurredAt().toEpochMilli());

        // Optional authentication event information.
        putNullable(data, "username", event.username());
        putNullable(data, "department", event.department());
        putNullable(data, "clientId", event.clientId());

        putNullable(
                data,
                "chainId",
                event.chainId() != null
                        ? event.chainId().toString()
                        : null
        );

        putNullable(data, "ipAddress", event.ipAddress());
        putNullable(data, "userAgent", event.userAgent());

        return data;
    }

    private void putNullable(
            ObjectNode data,
            String fieldName,
            String value
    ) {
        if (value == null) {
            data.putNull(fieldName);
        } else {
            data.put(fieldName, value);
        }
    }
}
