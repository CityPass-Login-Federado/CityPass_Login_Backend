package citypass.loginfederado.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record EdaEventEnvelope(
        String type,
        int version,
        Instant occurredAt,
        Metadata metadata,
        JsonNode data
) {
    public record Metadata(String source, String namespace, String tokenId) {
    }
}