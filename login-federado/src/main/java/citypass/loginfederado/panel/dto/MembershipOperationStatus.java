package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resultado de una combinación usuario-grupo.")
public enum MembershipOperationStatus {
    ASSIGNED,
    ALREADY_MEMBER,
    FAILED
}
