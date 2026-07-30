package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PaginatedResponse<T>(
    @Schema(description = "The results on this page") List<T> results, @Schema(description = "Zero-based index of this page") int page,
    @Schema(description = "Total number of results across all pages") int total
) {

    public PaginatedResponse {
        results = results == null ? List.of() : results;
    }
}
