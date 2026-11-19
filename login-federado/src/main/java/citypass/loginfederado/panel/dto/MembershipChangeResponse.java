package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Respuesta de cambios de membresía: el grupo resultante + avisos
 * preventivos (D5: advertencia desde 30 grupos, bloqueo en 50).
 */
@Schema(description = "Resultado de un cambio de membresía: grupo resultante + avisos preventivos (D5).")
public record MembershipChangeResponse(
        @Schema(description = "Grupo resultante tras el cambio") GroupView group,
        @Schema(description = "Avisos preventivos (ej: acumula 30+ grupos)") List<String> warnings
) {
}