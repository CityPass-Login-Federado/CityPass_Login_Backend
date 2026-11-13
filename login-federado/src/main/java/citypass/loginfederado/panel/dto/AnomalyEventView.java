package citypass.loginfederado.panel.dto;

import citypass.loginfederado.model.AnomalyEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Evento de autenticación bloqueado por detección de anomalías.")
public record AnomalyEventView(
        UUID id,
        String userSub,
        String username,
        String module,
        String clientId,
        String ipAddress,
        String userAgent,
        double riskScore,
        List<String> reasons,
        AnomalyEventStatus status,
        Instant blockedAt,
        Instant approvedAt,
        Instant approvalExpiresAt,
        String approvedBySub,
        String approvedByUid,
        Instant consumedAt
) {
}

