package citypass.loginfederado.service;

import citypass.loginfederado.dto.AnomalyScoreResponse;
import citypass.loginfederado.identity.LdapDirectoryPerson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AnomalyAssessmentService {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    public AnomalyAssessmentService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void recordAssessment(
            LdapDirectoryPerson person,
            String clientId,
            AnomalyScoreResponse assessment,
            String accessDisposition,
            UUID blockedEventId
    ) {

        String assessmentJson;

        try {
            assessmentJson =
                    objectMapper.writeValueAsString(
                            assessment
                    );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "No se pudo serializar la evaluación ML",
                    ex
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO anomaly_assessments (
                    id,
                    user_sub,
                    username,
                    module,
                    client_id,
                    assessed_at,
                    risk_score,
                    proposed_decision,
                    effective_decision,
                    access_disposition,
                    model_version,
                    model_sha256,
                    policy_sha256,
                    mode,
                    assessment_json,
                    blocked_event_id
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,

                UUID.randomUUID(),
                person.sub(),
                person.uid(),
                person.module(),
                clientId,
                OffsetDateTime.now(ZoneOffset.UTC),
                assessment.riskScore(),
                assessment.proposedDecision(),
                assessment.decision(),
                accessDisposition,
                assessment.modelVersion(),
                assessment.modelSha256(),
                assessment.policySha256(),
                assessment.mode(),
                assessmentJson,
                blockedEventId
        );
    }
}