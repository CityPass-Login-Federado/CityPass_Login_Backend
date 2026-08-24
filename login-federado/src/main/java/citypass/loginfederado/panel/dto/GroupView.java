package citypass.loginfederado.panel.dto;

import java.util.List;

/**
 * Vista de grupo. El placeholder técnico NUNCA aparece en members (el
 * delegado no sabe que existe — spec §2.6). reserved=true marca el grupo
 * delegados: no se renombra ni borra.
 */
public record GroupView(
        String name,
        List<String> members,
        boolean reserved
) {
}
