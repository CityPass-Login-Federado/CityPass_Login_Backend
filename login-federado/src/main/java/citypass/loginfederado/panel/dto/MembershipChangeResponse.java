package citypass.loginfederado.panel.dto;

import java.util.List;

/**
 * Respuesta de cambios de membresía: el grupo resultante + avisos
 * preventivos (D5: advertencia desde 30 grupos, bloqueo en 50).
 */
public record MembershipChangeResponse(
        GroupView group,
        List<String> warnings
) {
}
