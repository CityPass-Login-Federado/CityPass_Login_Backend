package citypass.loginfederado.panel.dto;

import java.util.List;

public record PaginatedResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int currentPage,
        int size
) {}
