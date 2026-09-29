package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * The facet categories that match a search, capped at {@link FacetTool#MAX_CATEGORIES} categories and
 * {@link FacetTool#MAX_FACETS_PER_CATEGORY} facets per category.
 *
 * @param query the search text, empty for none
 * @param categories the categories returned
 * @param categoriesOmitted how many categories the cap dropped
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FacetResult(String query, List<Category> categories, @Schema(requiredMode = NOT_REQUIRED) Integer categoriesOmitted) {

    /**
     * One facet category.
     *
     * @param name the category name
     * @param display the display name
     * @param description the description
     * @param facets the facets returned
     * @param facetsOmitted how many facets the cap dropped
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Category(
        String name, @Schema(requiredMode = NOT_REQUIRED) String display, @Schema(requiredMode = NOT_REQUIRED) String description,
        List<Facet> facets, @Schema(requiredMode = NOT_REQUIRED) Integer facetsOmitted
    ) {
    }

    /**
     * One facet.
     *
     * @param name the facet name
     * @param display the display name
     * @param count the number of matching concepts
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Facet(
        String name, @Schema(requiredMode = NOT_REQUIRED) String display, @Schema(requiredMode = NOT_REQUIRED) Integer count
    ) {
    }
}
