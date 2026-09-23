package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Advertencia preventiva única por usuario al acercarse al máximo de grupos.")
public record MembershipWarning(
        @Schema(description = "UID de la persona", example = "usuario1") String memberUid,
        @Schema(description = "Cantidad final de grupos luego de las asignaciones efectivas", example = "30") int totalGroups,
        @Schema(description = "Mensaje preventivo") String message
) {
}
