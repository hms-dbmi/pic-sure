package edu.harvard.dbmi.avillach.contracts.query.v3;


import java.util.List;

public record PaginatedResponse<T>(List<T> results, int page, int total) {

    public PaginatedResponse {
        results = results == null ? List.of() : results;
    }
}
