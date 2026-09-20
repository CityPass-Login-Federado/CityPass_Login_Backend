package citypass.loginfederado.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Canje del token de recupero (self-service, público): el enlace del mail
 * trae el token crudo; acá viaja junto a la NUEVA contraseña elegida por el
 * usuario. Token inválido, usado o vencido → mismo 422 genérico (los tokens
 * son aleatorios de 256 bits: no hay oráculo de enumeración).
 */
@Schema(description = "Canje de token de recupero: define la nueva contraseña.")
public record ResetPasswordRequest(
        @NotBlank(message = "El token es obligatorio")
        @Schema(description = "Token crudo del enlace de recupero", example = "dGhpcyBpcyBhIHRlc3QgdG9rZW4") String token,
        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 8, message = "La nueva contraseña debe tener al menos 8 caracteres")
        @Schema(description = "Nueva contraseña (mín 8 chars)", example = "nuevaClaveFu553") String newPassword
) {
}
