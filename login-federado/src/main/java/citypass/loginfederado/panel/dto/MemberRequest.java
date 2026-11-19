package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Cuerpo de alta de membresía: el UID de la persona a agregar.")
public record MemberRequest(
        @NotBlank
        @Schema(description = "UID de la persona a agregar", example = "test-user") String memberUid
) {
}