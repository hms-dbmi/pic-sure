package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(description = "One page of the values that match a search.")
public class PaginatedSearchResult<T> {
    @Schema(
        description = "The values on this page that match the search. What they hold depends on the concept searched. The example is "
            + "gene names, which a search of `Gene_with_variant` returns.",
        example = "[\"APOE\", \"APOC1\"]", requiredMode = Schema.RequiredMode.REQUIRED
    )
    private final List<T> results;

    @Schema(description = "The number of this page, starting at 1.", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int page;

    @Schema(description = "The number of matches across all pages.", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
    private final int total;

    @JsonCreator
    public PaginatedSearchResult(
        @JsonProperty("results") List<T> results, @JsonProperty("page") int page, @JsonProperty("total") int total
    ) {
        this.results = results;
        this.page = page;
        this.total = total;
    }

    public List<T> getResults() {
        return results;
    }

    public int getPage() {
        return page;
    }

    public int getTotal() {
        return total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaginatedSearchResult<?> that = (PaginatedSearchResult<?>) o;
        return page == that.page && total == that.total && Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, page, total);
    }
}
