package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A single page of results, plus the paging metadata needed to request the rest")
public record PaginatedResponse<T>(
    @Schema(description = "The results on this page") List<T> results,
    @Schema(
        description = "Index of this page. The base is defined by the serving endpoint, not by this record: HPDS's "
            + "GET /PIC-SURE/v3/search/values/ (and the query-service passthrough in front of it) is 1-based and rejects page < 1, "
            + "while the dictionary's /concepts endpoints are 0-based. See the page parameter on the endpoint you are calling."
    ) int page, @Schema(description = "Total number of results across all pages") int total
) {

    public PaginatedResponse {
        results = results == null ? List.of() : results;
    }
}
