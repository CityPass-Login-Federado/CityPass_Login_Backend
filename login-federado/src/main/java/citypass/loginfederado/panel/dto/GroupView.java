package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Vista de grupo. El placeholder técnico NUNCA aparece en members (el
 * delegado no sabe que existe — spec §2.6). reserved=true marca el grupo
 * delegados: no se renombra ni borra.
 */
@Schema(description = "Vista de grupo. reserved=true marca el grupo 'delegados'.")
public record GroupView(
        @Schema(description = "Nombre del grupo", example = "soporte-n2") String name,
        @Schema(description = "UIDs de las personas miembros (el placeholder técnico nunca aparece)") List<String> members,
        @Schema(description = "true si es el grupo reservado 'delegados'") boolean reserved
) {
}