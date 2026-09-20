package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corrección de datos de una persona. El employeeNumber es inmutable por
 * diseño (D3): no existe camino para cambiarlo, ni acá ni nunca.
 * newUsername dispara el renombre con reparación de membresías.
 */
@Schema(description = "Corrección de datos de una persona. Campos nulos = sin cambio. "
        + "El employeeNumber es inmutable (D3).")
public record UpdatePersonRequest(
        @Schema(description = "Nombre (opcional, null = sin cambio)", example = "Juan") String givenName,
        @Schema(description = "Apellido (opcional, null = sin cambio)", example = "Perez") String sn,
        @Schema(description = "Mail (opcional, null = sin cambio)", example = "jperez@citypass.local") String email,
        @Schema(description = "Nuevo username: dispara renombre con reparación de membresías",
                example = "juan.perez") String newUsername
) {
}