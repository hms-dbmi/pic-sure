package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The matches of a legacy search.")
public record Results(
    @Schema(
        description = "One entry per matching concept, at most the requested limit, best match first.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @JsonProperty("searchResults") List<SearchResult> searchResults
) {
}
