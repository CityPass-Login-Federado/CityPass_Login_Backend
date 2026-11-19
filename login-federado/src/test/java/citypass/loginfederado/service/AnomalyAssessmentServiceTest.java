package citypass.loginfederado.service;

import citypass.loginfederado.dto.AnomalyScoreResponse;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AnomalyAssessmentServiceTest {

    private final LdapDirectoryPerson person = new LdapDirectoryPerson(
            "uid=alice,ou=People,dc=citypass,dc=local",
            "U000042",
            "alice",
            "Alice Example",
            "alice@citypass.local",
            "reclamos",
            List.of("soporte")
    );

    @Test
    void recordAssessmentPersistsMetadataAndSerializedResponse() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AnomalyAssessmentService service =
                new AnomalyAssessmentService(jdbcTemplate, objectMapper);
        UUID blockedEventId = UUID.randomUUID();
        AnomalyScoreResponse assessment = new AnomalyScoreResponse(
                0.91,
                "BLOCK",
                List.of("IP nueva"),
                "BLOCK",
                "v4",
                "model-hash",
                "policy-hash",
                "successful_history_v1",
                "ENFORCE",
                Map.of("is_new_ip", 1)
        );

        service.recordAssessment(
                person,
                "citypass-reclamos-web",
                assessment,
                "BLOCKED",
                blockedEventId
        );

        ArgumentCaptor<Object[]> parameters =
                ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), parameters.capture());

        assertThat(sql.getValue()).contains(
                "INSERT INTO anomaly_assessments",
                "assessment_json",
                "blocked_event_id"
        );

        Object[] values = parameters.getValue();
        assertThat(values).hasSize(16);
        assertThat(values[0]).isInstanceOf(UUID.class);
        assertThat(values[1]).isEqualTo(person.sub());
        assertThat(values[2]).isEqualTo(person.uid());
        assertThat(values[3]).isEqualTo(person.module());
        assertThat(values[4]).isEqualTo("citypass-reclamos-web");
        assertThat(values[5]).isInstanceOf(OffsetDateTime.class);
        assertThat(((OffsetDateTime) values[5]).getOffset())
                .isEqualTo(ZoneOffset.UTC);
        assertThat(values[6]).isEqualTo(0.91);
        assertThat(values[7]).isEqualTo("BLOCK");
        assertThat(values[8]).isEqualTo("BLOCK");
        assertThat(values[9]).isEqualTo("BLOCKED");
        assertThat(values[10]).isEqualTo("v4");
        assertThat(values[11]).isEqualTo("model-hash");
        assertThat(values[12]).isEqualTo("policy-hash");
        assertThat(values[13]).isEqualTo("ENFORCE");
        assertThat(values[15]).isEqualTo(blockedEventId);

        JsonNode serialized = objectMapper.readTree((String) values[14]);
        assertThat(serialized.path("risk_score").asDouble()).isEqualTo(0.91);
        assertThat(serialized.path("features").path("is_new_ip").asInt())
                .isEqualTo(1);
    }

    @Test
    void serializationFailureDoesNotWriteAssessment() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("serialization failed") { });
        AnomalyAssessmentService service =
                new AnomalyAssessmentService(jdbcTemplate, objectMapper);

        assertThatThrownBy(() -> service.recordAssessment(
                person,
                "citypass-reclamos-web",
                new AnomalyScoreResponse(0.1, "ALLOW", List.of()),
                "PERMITTED_BY_ML",
                null
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No se pudo serializar la evaluación ML")
                .hasCauseInstanceOf(JsonProcessingException.class);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }
}
