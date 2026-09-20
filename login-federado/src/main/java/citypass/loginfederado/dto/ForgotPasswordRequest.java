package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Solicitud de recupero (self-service). La respuesta es SIEMPRE exitosa,
 * exista o no el usuario: el endpoint no puede usarse para enumerar
 * el directorio (misma regla que el login).
 *
 * El campo es el UID (uid en LDAP, ej: jperez). Se acepta "username" como
 * alias legacy para no romper clientes existentes.
 */
@Schema(description = "Solicitud de recupero: se manda un mail con un enlace de un solo uso.")
public record ForgotPasswordRequest(
        @JsonAlias("username")
        @NotBlank(message = "El usuario es obligatorio")
        @Schema(description = "UID del usuario (uid en LDAP)", example = "jperez") String uid
) {
}
