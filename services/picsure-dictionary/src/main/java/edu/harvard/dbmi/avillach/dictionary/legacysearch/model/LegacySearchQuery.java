package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The request body of the legacy search, in the shape the search that came before the dictionary took: one {@code query} object holding the
 * search text and the result limit. The name mirrors the legacy {@code /search} body's own vocabulary, where the endpoint is the legacy
 * search and the body is the query, and the record is the request envelope of that endpoint.
 *
 * @param query the search text and result limit
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A search request in the shape used before the dictionary existed.")
public record LegacySearchQuery(
    @Schema(description = "The search text and result limit.", requiredMode = Schema.RequiredMode.REQUIRED) LegacySearchCriteria query
) {
}
