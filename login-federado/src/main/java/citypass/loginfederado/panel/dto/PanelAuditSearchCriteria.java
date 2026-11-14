package citypass.loginfederado.panel.dto;

import java.time.Instant;

/** Filtros y paginación para consultar la auditoría administrativa. */
public record PanelAuditSearchCriteria(
        String actor,
        String action,
        Instant from,
        Instant to,
        String module,
        int page,
        int size
) { }
