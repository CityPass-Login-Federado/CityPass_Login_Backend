package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Vista de persona para el panel. `disabled` sale del atributo
 * pwdAccountLockedTime (D7): no hay flag propio, la baja es del directorio.
 */
@Schema(description = "Vista de persona para el panel. disabled = baja vía ppolicy (pwdAccountLockedTime).")
public record PersonView(
        @Schema(description = "ID estable, formato U000000 (inmutable, D3)", example = "U000042") String employeeNumber,
        @Schema(description = "Nombre de usuario (uid)", example = "jperez") String uid,
        @Schema(description = "Nombre", example = "Juan") String givenName,
        @Schema(description = "Apellido", example = "Perez") String sn,
        @Schema(description = "Mail", example = "jperez@citypass.local") String email,
        @Schema(description = "true si la cuenta está bloqueada vía ppolicy (D7)") boolean disabled
) {
}