package citypass.loginfederado.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * El client_id identifica a la aplicación que pide el token; te lo asigna el
 * Grupo 2 junto con tu audience (contrato público §2). El chequeo de módulo
 * del login compara la OU de la ficha contra el módulo registrado para este
 * cliente.
 */
@Schema(description = "Credenciales de login. El client_id identifica la aplicación; "
        + "su módulo registrado debe coincidir con la OU de la ficha LDAP.")
public record LoginRequest(
        @NotBlank(message = "El usuario es obligatorio")
        @Schema(description = "Nombre de usuario (uid en LDAP)", example = "jperez") String username,
        @NotBlank(message = "La contraseña es obligatoria")
        @Schema(description = "Contraseña en texto plano", example = "changeit123") String password,
        @NotBlank(message = "El client_id es obligatorio")
        @Schema(description = "client_id de la aplicación (ej: citypass-admin-web, citypass-reclamos-web)",
                example = "citypass-admin-web") String clientId
) {
}