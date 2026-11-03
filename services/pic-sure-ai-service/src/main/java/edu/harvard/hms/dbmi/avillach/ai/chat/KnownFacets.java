package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The facets seen during one chat turn, used to refuse a proposed facet selection naming a facet the model was never shown -- the facet
 * counterpart of {@link ConceptPaths}. A facet counts as seen if a {@code list_facets} result this turn listed it (a category's
 * {@code name} plus one of its facets' {@code name}) or the researcher's current selection already had it. Since the dictionary answers
 * {@code list_facets} for the caller's own JWT, a facet that came back is both real and visible to this caller. One instance per chat turn;
 * not thread-safe.
 */
final class KnownFacets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<FacetSelection> seen = new HashSet<>();

    /**
     * Records the researcher's current selection.
     *
     * @param facets the selection from the request, possibly null
     */
    void learnFrom(List<FacetSelection> facets) {
        if (facets != null) {
            seen.addAll(facets);
        }
    }

    /**
     * Records every facet in a {@code list_facets} result: {@code {categories:[{name, facets:[{name}]}]}}. Content that isn't JSON, or isn't
     * that shape, is ignored.
     *
     * @param json a {@code list_facets} tool result
     */
    void learnFromListFacets(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            for (JsonNode category : MAPPER.readTree(json).path("categories")) {
                String categoryName = category.path("name").asText(null);
                for (JsonNode facet : category.path("facets")) {
                    String facetName = facet.path("name").asText(null);
                    if (categoryName != null && facetName != null) {
                        seen.add(new FacetSelection(categoryName, facetName));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // Not JSON, so no facets to learn from it.
        }
    }

    /**
     * @param facet a proposed facet
     * @return whether this turn has seen it
     */
    boolean isKnown(FacetSelection facet) {
        return seen.contains(facet);
    }

    /**
     * @param facets a proposed selection
     * @return the first facet this turn hasn't seen, or null if all were seen
     */
    FacetSelection firstUnknownIn(List<FacetSelection> facets) {
        for (FacetSelection facet : facets) {
            if (!isKnown(facet)) {
                return facet;
            }
        }
        return null;
    }
}
