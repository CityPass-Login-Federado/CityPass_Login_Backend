package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Resultado completo de una asignación masiva de membresías.")
public record BulkMembershipResponse(
        @Schema(description = "Estado global del lote", example = "PARTIAL") BulkMembershipStatus status,
        @Schema(description = "Combinaciones solicitadas tras normalizar y deduplicar", example = "4") int requested,
        @Schema(description = "Relaciones creadas", example = "2") int assigned,
        @Schema(description = "Relaciones preexistentes", example = "1") int skipped,
        @Schema(description = "Relaciones que no pudieron crearse", example = "1") int failed,
        @Schema(description = "Detalle determinista, por grupo y luego por usuario") List<MembershipOperationResult> results,
        @Schema(description = "Advertencias únicas por usuario calculadas con el resultado efectivo") List<MembershipWarning> warnings
) {
}
