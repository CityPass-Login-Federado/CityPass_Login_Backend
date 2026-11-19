package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Cuerpo de reset de contraseña: la nueva contraseña temporal.")
public record PasswordResetRequest(
        @NotBlank
        @Schema(description = "Nueva contraseña temporal (mín 8 chars)", example = "nueva-temp-456") String temporaryPassword
) {
}