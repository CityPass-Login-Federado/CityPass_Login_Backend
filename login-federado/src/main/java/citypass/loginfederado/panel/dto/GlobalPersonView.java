package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Persona con su módulo para el listado global legado del panel.")
public record GlobalPersonView(
        @Schema(description = "Módulo de la ficha", example = "reclamos") String module,
        @Schema(description = "ID estable", example = "U000042") String employeeNumber,
        @Schema(description = "Nombre de usuario", example = "jperez") String uid,
        @Schema(description = "Nombre", example = "Juan") String givenName,
        @Schema(description = "Apellido", example = "Perez") String sn,
        @Schema(description = "Mail", example = "jperez@citypass.local") String email,
        @Schema(description = "true si la cuenta está deshabilitada en LDAP") boolean disabled,
        @Schema(description = "true si está activo el bloqueo temporal por intentos fallidos") boolean locked,
        @Schema(description = "Fin del bloqueo temporal activo; null cuando locked=false",
                type = "string", format = "date-time", nullable = true)
        Instant lockoutUntil
) {
    public GlobalPersonView withLockout(boolean currentLocked, Instant currentLockoutUntil) {
        return new GlobalPersonView(module, employeeNumber, uid, givenName, sn, email, disabled,
                currentLocked, currentLockoutUntil);
    }
}
