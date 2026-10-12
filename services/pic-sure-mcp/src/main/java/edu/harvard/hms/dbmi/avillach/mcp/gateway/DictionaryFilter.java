package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import java.util.List;

/**
 * The request body the dictionary's search and facet endpoints take. This service always sends no consents, which is the open view.
 *
 * @param facets the facets to narrow by, empty for none
 * @param search the free-text search, or an empty string for none
 * @param consents the consent filter, always empty here
 */
public record DictionaryFilter(List<FacetFilter> facets, String search, List<String> consents) {

    /**
     * Builds the open-view filter for a search text.
     *
     * @param search the free-text search, or null for none
     * @return a filter with empty facets and empty consents
     */
    public static DictionaryFilter open(String search) {
        return open(search, List.of());
    }

    /**
     * Builds the open-view filter for a search text narrowed by facets.
     *
     * @param search the free-text search, or null for none
     * @param facets the facets to narrow by, or null for none
     * @return a filter with those facets and empty consents
     */
    public static DictionaryFilter open(String search, List<FacetFilter> facets) {
        return new DictionaryFilter(facets == null ? List.of() : List.copyOf(facets), search == null ? "" : search, List.of());
    }
}
