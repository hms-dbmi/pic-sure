package edu.harvard.dbmi.avillach.dictionary.facet;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A group of facets of one kind, such as every study.")
public record FacetCategory(
    @Schema(
        description = "Identifier of the category.", example = "study_ids_dataset_ids", requiredMode = Schema.RequiredMode.REQUIRED
    ) String name,
    @Schema(
        description = "Name of the category shown to users.", example = "Study IDs/Dataset IDs", requiredMode = Schema.RequiredMode.REQUIRED
    ) String display,
    @Schema(
        description = "Longer text about the category. Empty when the dictionary holds none.",
        example = "Studies and datasets that concepts belong to"
    ) String description,
    @Schema(
        description = "The facets in this category, each with its count for the filter.", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<Facet> facets
) {
    public FacetCategory(FacetCategory core, List<Facet> facets) {
        this(core.name(), core.display(), core.description(), facets);
    }
}
