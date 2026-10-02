package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Search results in the shape returned by the search that came before the dictionary.")
public record LegacyResponse(
    @Schema(description = "Wrapper around the list of matches.", requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty(
        "results"
    ) Results results
) {
}
