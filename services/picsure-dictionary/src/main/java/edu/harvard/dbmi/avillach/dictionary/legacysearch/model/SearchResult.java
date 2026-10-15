package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One match of a legacy search.")
public record SearchResult(
    @Schema(description = "The matching concept.", requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty("result") Result result
) {
}
