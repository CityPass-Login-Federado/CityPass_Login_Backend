package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Vista de persona para el listado transversal del admin global: igual que
 * {@link PersonView} más el módulo donde vive la ficha (sin él, el operador
 * no sabría sobre qué módulo operar después).
 */
@Schema(description = "Persona con su módulo (solo admin global, todos los módulos).")
public record AdminPersonView(
        @Schema(description = "Módulo de la ficha", example = "reclamos") String module,
        @Schema(description = "ID estable, formato U000000 (inmutable, D3)", example = "U000042") String employeeNumber,
        @Schema(description = "Nombre de usuario (uid)", example = "jperez") String uid,
        @Schema(description = "Nombre", example = "Juan") String givenName,
        @Schema(description = "Apellido", example = "Perez") String sn,
        @Schema(description = "Mail", example = "jperez@citypass.local") String email,
        @Schema(description = "true si la cuenta está deshabilitada en LDAP vía ppolicy (D7)") boolean disabled,
        @Schema(description = "true únicamente mientras está activo el bloqueo temporal por intentos fallidos")
        boolean locked,
        @Schema(description = "Fin del bloqueo temporal activo; null cuando locked=false",
                type = "string", format = "date-time", nullable = true)
        Instant lockoutUntil
) {
    public static AdminPersonView of(String module, PersonView person) {
        return new AdminPersonView(module, person.employeeNumber(), person.uid(),
                person.givenName(), person.sn(), person.email(), person.disabled(),
                person.locked(), person.lockoutUntil());
    }

    public AdminPersonView withLockout(boolean currentLocked, Instant currentLockoutUntil) {
        return new AdminPersonView(module, employeeNumber, uid, givenName, sn, email, disabled,
                currentLocked, currentLockoutUntil);
    }
}
