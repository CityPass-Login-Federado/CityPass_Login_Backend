package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Registro de una acción administrativa realizada desde el panel.")
public record PanelAuditView(
        @Schema(description = "Identificador del evento") UUID id,
        @Schema(description = "Identificador estable del administrador", example = "U000042") String actorSub,
        @Schema(description = "Nombre de usuario del administrador", example = "jperez") String actorUid,
        @Schema(description = "Módulo afectado", example = "reclamos") String module,
        @Schema(description = "Código de acción", example = "PERSON_CREATED") String action,
        @Schema(description = "Identificador del recurso afectado") String target,
        @Schema(description = "Detalle adicional de la acción", nullable = true) String detail,
        @Schema(description = "Instante en que ocurrió la acción") Instant occurredAt
) { }
