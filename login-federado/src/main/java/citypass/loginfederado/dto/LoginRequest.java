package citypass.loginfederado.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * El client_id identifica a la aplicación que pide el token; te lo asigna el
 * Grupo 2 junto con tu audience (contrato público §2). El chequeo de módulo
 * del login compara la OU de la ficha contra el módulo registrado para este
 * cliente.
 */
public record LoginRequest(
        @NotBlank(message = "El usuario es obligatorio") String username,
        @NotBlank(message = "La contraseña es obligatoria") String password,
        @NotBlank(message = "El client_id es obligatorio") String clientId
) {
}
