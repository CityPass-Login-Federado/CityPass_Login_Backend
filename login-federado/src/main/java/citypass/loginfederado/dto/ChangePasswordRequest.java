package citypass.loginfederado.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cambio de contraseña desde el perfil. Exige la contraseña ACTUAL: el poseerla
 * es el segundo factor (además del JWT) que confirma que el dueño de la sesión
 * es la persona real y no un token robado.
 */
@Schema(description = "Cambio de contraseña desde el perfil: requiere la contraseña actual.")
public record ChangePasswordRequest(
        @NotBlank(message = "La contraseña actual es obligatoria")
        @Schema(description = "Contraseña vigente (se valida contra LDAP)", example = "changeit123") String currentPassword,
        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 8, message = "La nueva contraseña debe tener al menos 8 caracteres")
        @Schema(description = "Nueva contraseña (mín 8 chars)", example = "nuevaClaveFu553") String newPassword
) {
}