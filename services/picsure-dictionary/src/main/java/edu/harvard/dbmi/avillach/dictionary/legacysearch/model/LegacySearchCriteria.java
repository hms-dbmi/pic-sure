package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The two values the legacy search reads from the {@code query} object of its request. The older clients also send {@code includedTags},
 * {@code excludedTags}, {@code returnTags} and {@code offset}; the dictionary has never read them, so they are accepted and ignored.
 *
 * @param searchTerm the text to search for, where {@code |} separates alternatives
 * @param limit the largest number of results to return
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "The search text and result limit of a legacy search. Any other key the older clients send here is ignored.")
public record LegacySearchCriteria(
    @Schema(
        description = "Text to search for. Words are matched by prefix and must all match; a | separates alternatives. "
            + "Omitted or null searches with no text.",
        example = "age"
    ) String searchTerm,
    @Schema(
        description = "Largest number of results to return, 1 or more. A numeric string is accepted.", example = "100",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Integer limit
) {
}
