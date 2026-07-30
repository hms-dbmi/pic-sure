package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The {@code /search} response envelope. The envelope field keeps its legacy name so the outer shape is recognisable, but its payload is
 * now the same {@link Concept} model every other dictionary endpoint returns rather than a parallel legacy result/metadata hierarchy.
 */
@Schema(description = "Concepts matching a free-text search")
public record LegacyResponse(
    @JsonProperty("results") @Schema(description = "Concepts matching the search term, best match first") List<Concept> results
) {

    public LegacyResponse {
        results = results == null ? List.of() : results;
    }
}
