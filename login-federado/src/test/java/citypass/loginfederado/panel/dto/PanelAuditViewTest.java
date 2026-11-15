package citypass.loginfederado.panel.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PanelAuditViewTest {

    @Test
    void serializesOnlyThePublicAuditContract() throws Exception {
        PanelAuditView view = new PanelAuditView(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "U000001",
                "jperez",
                "reclamos",
                "PERSON_CREATED",
                "jperez",
                "alta",
                Instant.parse("2026-11-14T12:00:00Z")
        );

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var json = objectMapper.readTree(objectMapper.writeValueAsString(view));

        assertThat(json.properties())
                .extracting(entry -> entry.getKey())
                .containsExactlyInAnyOrder(
                        "id", "actorSub", "actorUid", "module", "action", "target", "detail", "occurredAt");
    }
}
