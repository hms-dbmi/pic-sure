package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One facet category as the dictionary's {@code /facets} endpoint returns it.
 *
 * @param name the category name
 * @param display the category display name
 * @param description the category description
 * @param facets the facets in the category, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FacetCategory(String name, String display, String description, List<Facet> facets) {

    /**
     * One facet with the number of concepts it matches.
     *
     * @param name the facet name
     * @param display the facet display name
     * @param description the facet description
     * @param count the number of matching concepts, or null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Facet(String name, String display, String description, Integer count) {
    }
}
