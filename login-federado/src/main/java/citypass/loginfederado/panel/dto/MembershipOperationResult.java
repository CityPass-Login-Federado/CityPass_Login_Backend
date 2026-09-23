package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resultado detallado de una combinación usuario-grupo del lote.")
public record MembershipOperationResult(
        @Schema(description = "UID normalizado de la persona", example = "usuario1") String memberUid,
        @Schema(description = "Nombre normalizado del grupo", example = "grupo-a") String groupName,
        @Schema(description = "Estado de la combinación", example = "ASSIGNED") MembershipOperationStatus status,
        @Schema(description = "Mensaje seguro cuando la relación fue omitida o falló", nullable = true,
                example = "El usuario ya pertenecía al grupo") String message
) {
}
