package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import java.util.List;

/**
 * The request body the dictionary's search and facet endpoints take. This service always sends no facets and no consents, which is the open
 * view.
 *
 * @param facets the selected facets, always empty here
 * @param search the free-text search, or an empty string for none
 * @param consents the consent filter, always empty here
 */
public record DictionaryFilter(List<Object> facets, String search, List<String> consents) {

    /**
     * Builds the open-view filter for a search text.
     *
     * @param search the free-text search, or null for none
     * @return a filter with empty facets and empty consents
     */
    public static DictionaryFilter open(String search) {
        return new DictionaryFilter(List.of(), search == null ? "" : search, List.of());
    }
}
