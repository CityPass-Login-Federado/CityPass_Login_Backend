package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Usuarios y grupos que forman el producto cartesiano de membresías a crear.")
public record BulkMembershipRequest(
        @NotNull
        @Size(min = 1, max = 1000)
        @Valid
        @Schema(description = "UIDs de personas. Se recortan espacios y se eliminan duplicados preservando el orden.",
                example = "[\"usuario1\", \"usuario2\"]")
        List<@NotBlank String> memberUids,

        @NotNull
        @Size(min = 1, max = 1000)
        @Valid
        @Schema(description = "Nombres de grupos. Se recortan espacios y se eliminan duplicados preservando el orden.",
                example = "[\"grupo-a\", \"grupo-b\"]")
        List<@NotBlank String> groupNames
) {
}
