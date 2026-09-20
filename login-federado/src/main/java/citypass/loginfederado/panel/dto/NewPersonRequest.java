package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Alta de persona. El employeeNumber NO viene del cliente: lo asigna el sistema (D3). */
@Schema(description = "Alta de persona. El employeeNumber lo asigna el sistema (D3): no viaja en el request.")
public record NewPersonRequest(
        @NotBlank
        @Schema(description = "Nombre", example = "Test") String givenName,
        @NotBlank
        @Schema(description = "Apellido", example = "Usuario") String sn,
        @NotBlank
        @Schema(description = "Nombre de usuario (3-32 chars, a-z 0-9 . _ -), único global",
                example = "test.user") String username,
        @NotBlank
        @Schema(description = "Mail, único global", example = "test.user@citypass.local") String email,
        @NotBlank
        @Schema(description = "Contraseña inicial (mín 8 chars); el usuario debe cambiarla al entrar",
                example = "temporal123") String temporaryPassword
) {
}