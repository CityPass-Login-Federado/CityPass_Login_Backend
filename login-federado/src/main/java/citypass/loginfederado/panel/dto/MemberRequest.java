package citypass.loginfederado.panel.dto;

import jakarta.validation.constraints.NotBlank;

public record MemberRequest(
        @NotBlank String memberUid
) {
}
