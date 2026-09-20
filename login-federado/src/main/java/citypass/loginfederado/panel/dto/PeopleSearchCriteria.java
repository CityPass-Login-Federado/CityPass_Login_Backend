package citypass.loginfederado.panel.dto;

public record PeopleSearchCriteria(
        int page,
        int size,
        String search,
        String group,
        Boolean disabled
) {}
