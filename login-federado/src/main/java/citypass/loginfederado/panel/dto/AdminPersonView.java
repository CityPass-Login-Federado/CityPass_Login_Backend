package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "true si la cuenta está bloqueada vía ppolicy (D7)") boolean disabled
) {
    public static AdminPersonView of(String module, PersonView person) {
        return new AdminPersonView(module, person.employeeNumber(), person.uid(),
                person.givenName(), person.sn(), person.email(), person.disabled());
    }
}
