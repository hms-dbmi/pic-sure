package edu.harvard.hms.dbmi.avillach.mcp.gateway;

/**
 * One facet to narrow a dictionary search by, in the shape the dictionary accepts: the category and the facet's name, both as
 * {@code list_facets} returns them. The UI sends a larger object (display text, count, children), but the dictionary only needs these two.
 *
 * @param category the facet category's name, such as {@code dataset_id}
 * @param name the facet's name within the category, such as {@code Nhanes}
 */
public record FacetFilter(String category, String name) {
}
