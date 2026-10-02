package edu.harvard.dbmi.avillach.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The matches for a search term")
public class SearchResults {

    @Schema(
        description = "The matches, shaped by the resource. HPDS returns an object with `phenotypes` and `info`, each a map keyed by "
            + "concept path or info column name."
    )
    Object results;

    @Schema(description = "The search term the results answer", example = "age")
    String searchQuery;

    public Object getResults() {
        return results;
    }

    public SearchResults setResults(Object results) {
        this.results = results;
        return this;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public SearchResults setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery;
        return this;
    }
}
