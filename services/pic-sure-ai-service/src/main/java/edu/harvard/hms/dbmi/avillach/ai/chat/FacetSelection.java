package edu.harvard.hms.dbmi.avillach.ai.chat;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One active facet in the researcher's current selection, in the slimmed shape {@code PLAN.md}'s Transport section calls for:
 * {@code category}/{@code name} only, not the UI's full {@code Facet} display tree ({@code count}, {@code children}, {@code categoryRef},
 * {@code parentRef}). That tree is UI-rendering state -- noise and cost for the model, not needed to identify which facets are active.
 *
 * @param category the facet category name, e.g. {@code study}
 * @param name the facet's own name within that category
 */
@Schema(description = "One active facet in the researcher's selection.")
public record FacetSelection(
    @Schema(description = "The facet category name.", example = "study") String category,
    @Schema(description = "The facet's name within its category.", example = "phs000001") String name
) {
}
