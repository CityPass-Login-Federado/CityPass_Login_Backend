package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Vista de persona para el panel. `disabled` sale del atributo LDAP
 * pwdAccountLockedTime (D7), mientras que `locked` representa exclusivamente
 * el lockout temporal por intentos fallidos.
 */
@Schema(description = "Vista de persona para el panel. disabled indica baja LDAP; locked indica lockout temporal.")
public record PersonView(
        @Schema(description = "ID estable, formato U000000 (inmutable, D3)", example = "U000042") String employeeNumber,
        @Schema(description = "Nombre de usuario (uid)", example = "jperez") String uid,
        @Schema(description = "Nombre", example = "Juan") String givenName,
        @Schema(description = "Apellido", example = "Perez") String sn,
        @Schema(description = "Mail", example = "jperez@citypass.local") String email,
        @Schema(description = "true si la cuenta está deshabilitada en LDAP vía ppolicy (D7)") boolean disabled,
        @Schema(description = "true únicamente mientras está activo el bloqueo temporal por intentos fallidos")
        boolean locked,
        @Schema(description = "Fin del bloqueo temporal activo; null cuando locked=false",
                type = "string", format = "date-time", nullable = true,
                example = "2026-11-15T15:30:00Z")
        Instant lockoutUntil
) {
    public PersonView withLockout(boolean currentLocked, Instant currentLockoutUntil) {
        return new PersonView(employeeNumber, uid, givenName, sn, email, disabled,
                currentLocked, currentLockoutUntil);
    }
}
