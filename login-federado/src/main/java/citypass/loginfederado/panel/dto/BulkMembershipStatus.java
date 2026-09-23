package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado global del lote: SUCCESS sin fallos, PARTIAL con éxitos/omisiones y fallos, FAILED solo con fallos.")
public enum BulkMembershipStatus {
    SUCCESS,
    PARTIAL,
    FAILED
}
