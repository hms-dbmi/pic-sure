package edu.harvard.dbmi.avillach.dictionary.util;

/**
 * Shared SQL fragments used by concept and facet query generators.
 */
public class QueryUtility {

    /**
     * CTE that determines whether each concept should be filterable in the UI. Concepts with disallowed meta keys set to 'true' (e.g.
     * stigmatized) are marked as non-filterable, which demotes them in search ranking. Expects a :disallowed_meta_keys named parameter.
     */
    public static final String ALLOW_FILTERING_Q = """
        WITH allow_filtering AS (
            SELECT
                concept_node_meta.concept_node_id AS concept_node_id,
                (concept_node_meta.value <> 'true') AS allowFiltering
            FROM
                concept_node_meta
            WHERE
                concept_node_meta.KEY IN (:disallowed_meta_keys)
        )
        """;

    /**
     * Converts a pre-sanitized search string into a prefix-matching tsquery. Input is sanitized by FilterProcessor.sanitizeSearch() before
     * reaching SQL — only contains Unicode letters, digits, and single spaces. Splits on whitespace, appends :* to each word, and ANDs them
     * together. e.g. "ear infection" becomes to_tsquery('english', 'ear:* & infection:*')
     */
    private static final String TSQUERY_EXPR = "to_tsquery('english', regexp_replace(trim(:search), '\\s+', ':* & ', 'g') || ':*')";

    public static final String SEARCH_QUERY = "ts_rank(searchable_fields, " + TSQUERY_EXPR + ")";

    /**
     * WHERE clause filter for full-text search. Uses the GIN-indexed searchable_fields tsvector column with prefix matching via to_tsquery.
     * Expects a :search named parameter that has been pre-sanitized by FilterProcessor.
     */
    public static final String SEARCH_WHERE = "concept_node.searchable_fields @@ " + TSQUERY_EXPR;
}
