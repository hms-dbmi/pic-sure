package edu.harvard.dbmi.avillach.dictionary.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    description = "What a concept search or a facet count is narrowed by. Every part is optional, and an empty filter matches every concept."
)
public record Filter(
    @Schema(
        description = "Facets a concept must carry. Within one facet category any of the listed facets may match, and every category listed must match. Only the name and category of each facet are read. Null or empty applies no facet restriction."
    ) @Nullable List<Facet> facets,
    @Schema(
        description = "Text to search concepts for. Characters other than letters, digits and whitespace are replaced by spaces and the text is cut at 200 characters. Null or blank applies no text search.",
        example = "age"
    ) @Nullable String search,
    @Schema(
        description = "Consents the caller holds, each a study accession with its consent group. When non-empty, only concepts of datasets under those consents are returned. Null or empty applies no consent restriction and returns every concept.",
        example = "[\"phs000007.c1\", \"phs000007.c2\"]"
    ) @Nullable List<String> consents
) {
}
