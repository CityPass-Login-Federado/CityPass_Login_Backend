package citypass.loginfederado.panel.dto;

import jakarta.validation.constraints.NotBlank;

/** Alta de persona. El employeeNumber NO viene del cliente: lo asigna el sistema (D3). */
public record NewPersonRequest(
        @NotBlank String givenName,
        @NotBlank String sn,
        @NotBlank String username,
        @NotBlank String email,
        @NotBlank String temporaryPassword
) {
}
