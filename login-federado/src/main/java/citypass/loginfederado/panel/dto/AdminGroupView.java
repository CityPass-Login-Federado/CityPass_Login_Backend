package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Vista de grupo para el listado transversal del admin global: igual que
 * {@link GroupView} más el módulo donde vive el grupo.
 */
@Schema(description = "Grupo con su módulo (solo admin global, todos los módulos).")
public record AdminGroupView(
        @Schema(description = "Módulo del grupo", example = "reclamos") String module,
        @Schema(description = "Nombre del grupo", example = "soporte-n2") String name,
        @Schema(description = "UIDs de las personas miembros (el placeholder técnico nunca aparece)") List<String> members,
        @Schema(description = "true si es el grupo reservado 'delegados'") boolean reserved
) {
    public static AdminGroupView of(String module, GroupView group) {
        return new AdminGroupView(module, group.name(), group.members(), group.reserved());
    }
}
