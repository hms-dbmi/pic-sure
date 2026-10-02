package edu.harvard.dbmi.avillach.dictionary.facet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    description = "A value concepts are grouped by, such as one study. The facet endpoints return it with a count, and a filter sends it back to narrow a search."
)
public record Facet(
    @Schema(
        description = "Identifier of the facet within its category.", example = "phs000007", requiredMode = Schema.RequiredMode.REQUIRED
    ) String name, @Schema(description = "Name of the facet shown to users.", example = "FHS") String display,
    @Schema(
        description = "Longer text about the facet. Null when the dictionary holds none.",
        example = "Concepts collected by the Framingham Heart Study"
    ) String description,
    @Schema(
        description = "Full name of the facet, from its full_name metadata. Null when none is recorded.", example = "Framingham Heart Study"
    ) String fullName,
    @Schema(
        description = "Number of concepts that carry this facet and match the filter. Null on the facet detail endpoint, and ignored in a request.",
        example = "128"
    ) @Nullable Integer count,
    @Schema(
        description = "Facets nested under this one. Empty when there are none, and ignored in a request."
    ) @Nullable List<Facet> children,
    @Schema(
        description = "Name of the facet category this facet belongs to.", example = "study_ids_dataset_ids",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String category,
    @Schema(
        description = "Facet metadata from the dictionary, keyed by metadata key such as full_name. Set only by the facet detail endpoint. Null elsewhere."
    ) @Nullable Map<String, String> meta
) {
    public Facet(Facet core, Map<String, String> meta) {
        this(core.name(), core.display(), core.description(), core.fullName(), core.count(), core.children(), core.category(), meta);
    }

    public Facet(String name, String category) {
        this(name, "", "", "", null, null, category, null);
    }

    public Facet withChildren(List<Facet> children) {
        return new Facet(this.name, this.display, this.description, this.fullName, this.count, children, this.category, this.meta);
    }
}
