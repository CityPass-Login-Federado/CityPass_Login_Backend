package citypass.loginfederado.panel.dto;

import jakarta.validation.constraints.NotBlank;

/** Creación de grupo. El nombre viaja crudo: el backend valida D6. */
public record GroupCreateRequest(
        @NotBlank String name
) {
}
