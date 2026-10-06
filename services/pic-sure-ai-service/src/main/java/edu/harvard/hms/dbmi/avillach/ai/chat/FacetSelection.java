package edu.harvard.hms.dbmi.avillach.ai.chat;

/**
 * One active facet in the researcher's current selection, in the slimmed shape {@code PLAN.md}'s Transport section calls for:
 * {@code category}/{@code name} only, not the UI's full {@code Facet} display tree ({@code count}, {@code children}, {@code categoryRef},
 * {@code parentRef}). That tree is UI-rendering state -- noise and cost for the model, not needed to identify which facets are active.
 *
 * @param category the facet category name, e.g. {@code study}
 * @param name the facet's own name within that category
 */
public record FacetSelection(String category, String name) {
}
