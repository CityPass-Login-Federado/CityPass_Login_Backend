package citypass.loginfederado.panel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Envoltura paginada devuelta por los listados del panel.")
public record PaginatedResponse<T>(
        @Schema(description = "Elementos de la página actual") List<T> content,
        @Schema(description = "Total de elementos en el módulo") long totalElements,
        @Schema(description = "Total de páginas") int totalPages,
        @Schema(description = "Página actual (base 0)") int currentPage,
        @Schema(description = "Tamaño de página") int size
) {}