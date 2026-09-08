package citypass.loginfederado.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Cuerpo de refresh y logout: el refresh token a canjear o revocar.")
public record RefreshRequest(
        @NotBlank(message = "El refresh token es obligatorio")
        @Schema(description = "Refresh token vigente", example = "rt_xxxx") String refreshToken
) {
}