package edu.harvard.dbmi.avillach.dictionary.util;

public class QueryUtility {

    public static final String ALLOW_FILTERING_Q = """
        WITH allow_filtering AS (
            SELECT
                concept_node.concept_node_id AS concept_node_id,
                (string_agg(concept_node_meta.value, ' ') NOT LIKE '%' || 'true' || '%') AS allowFiltering
            FROM
                concept_node
                JOIN concept_node_meta ON
                    concept_node.concept_node_id = concept_node_meta.concept_node_id
                    AND concept_node_meta.KEY IN (:disallowed_meta_keys)
            GROUP BY
                concept_node.concept_node_id
        )
        """;

    public static final String SEARCH_QUERY =
        "CAST(LOWER(categorical_values.VALUE) LIKE '%' || LOWER(:search) || '%' as integer) * 10 + ts_rank(searchable_fields, (phraseto_tsquery(:search)::text || ':*')::tsquery)";
    public static final String SEARCH_WHERE =
        "(LOWER(categorical_values.VALUE) LIKE '%' || LOWER(:search) || '%' OR concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery)";
}
