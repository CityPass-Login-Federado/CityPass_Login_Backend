package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Creación de grupo. El nombre viaja crudo: el backend valida D6. */
@Schema(description = "Creación de grupo. El nombre lo valida el backend (D6).")
public record GroupCreateRequest(
        @NotBlank
        @Schema(description = "Nombre del grupo (a-z, 0-9, guiones; máx 64)",
                example = "soporte-n2", pattern = "^[a-z0-9]+(-[a-z0-9]+)*$") String name
) {
}