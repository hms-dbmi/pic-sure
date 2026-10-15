package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One active facet in the researcher's current selection, in the slimmed shape {@code PLAN.md}'s Transport section calls for:
 * {@code category}/{@code name} only, not the UI's full {@code Facet} display tree ({@code count}, {@code children}, {@code categoryRef},
 * {@code parentRef}). That tree is UI-rendering state -- noise and cost for the model, not needed to identify which facets are active.
 *
 * @param category the facet category name, e.g. {@code study}
 * @param name the facet's own name within that category
 */
public record FacetSelection(String category, String name) {

    /** @return the {category, name} pairs in a tool call's {@code facets}, skipping malformed entries */
    static List<FacetSelection> listedIn(JsonNode arguments) {
        JsonNode facets = arguments.path("facets");
        if (!facets.isArray()) {
            return List.of();
        }
        List<FacetSelection> found = new ArrayList<>();
        for (JsonNode facet : facets) {
            if (facet.path("category").isTextual() && facet.path("name").isTextual()) {
                found.add(new FacetSelection(facet.path("category").asText(), facet.path("name").asText()));
            }
        }
        return found;
    }
}
