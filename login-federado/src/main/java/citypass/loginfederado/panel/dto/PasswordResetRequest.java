package citypass.loginfederado.panel.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @NotBlank String temporaryPassword
) {
}
