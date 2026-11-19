package citypass.loginfederado.panel.dto;

public record GroupSearchCriteria(
        int page,
        int size,
        String search,
        Boolean reserved
) {}
