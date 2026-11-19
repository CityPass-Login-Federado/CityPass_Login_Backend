package citypass.loginfederado.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Perfil propio (`GET /me`): datos de la persona autenticada releídos del
 * directorio en cada llamada — a diferencia del token, nunca desactualizados
 * (si cambian sus grupos, acá se ven al instante). No incluye secretos.
 */
@Schema(description = "Perfil de la persona autenticada.")
public record MeResponse(
        @Schema(description = "UID (preferred_username)", example = "jperez") String uid,
        @Schema(description = "employeeNumber: identificador estable (sub del JWT)", example = "U000042") String employeeNumber,
        @Schema(description = "Nombre completo (cn)", example = "Juan Perez") String fullName,
        @Schema(description = "Mail", example = "jperez@citypass.local") String email,
        @Schema(description = "Módulo de origen", example = "reclamos") String module,
        @Schema(description = "Grupos actuales (nombres pelados)", example = "[\"delegados\"]") List<String> groups
) {
}
