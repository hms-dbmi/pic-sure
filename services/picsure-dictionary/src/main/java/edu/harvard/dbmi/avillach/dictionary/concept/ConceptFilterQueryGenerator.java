package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ConceptFilterQueryGenerator {

    private static final String CONSENT_QUERY = """
                dataset.dataset_id IN (
                    SELECT
                        consent.dataset_id
                    FROM consent
                        LEFT JOIN dataset ON dataset.dataset_id = consent.dataset_id
                    WHERE
                        concat(dataset.ref, '.', consent.consent_code) IN (:consents) OR
                        (dataset.ref IN (:consents) AND consent.consent_code = '')
                ) AND
                """;

    /**
     * This generates a query that will return a list of concept_node IDs for the given filter.
     * <p>
     * A filter object has a list of facets, each belonging to a category. Within a category,
     * facets are ORed together. Between categories, facets are ANDed together.
     * In SQL, this is represented as N clauses for N facets, all INTERSECTed together. Search
     * also acts as its own special facet here.
     *
     * @param filter universal filter object for the page
     * @param pageable pagination, if applicable
     * @return the query and parameters needed to get a list of concepts for the filter
     */
    public QueryParamPair generateFilterQuery(Filter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> clauses = new java.util.ArrayList<>(List.of());
        if (!CollectionUtils.isEmpty(filter.facets())) {
            clauses.addAll(createFacetFilter(filter, params));
        }
        if (StringUtils.hasLength(filter.search())) {
            params.addValue("search", filter.search().trim());
        }
        if (!CollectionUtils.isEmpty(filter.consents())) {
            params.addValue("consents", filter.consents());
        }
        clauses.add(createValuelessNodeFilter(filter.search(), filter.consents()));


        String query = "(\n" + String.join("\n\tINTERSECT\n", clauses) + "\n)";
        String superQuery = """
            WITH q AS (
                %s
            )
            SELECT concept_node_id
            FROM q
            GROUP BY concept_node_id
            ORDER BY max(rank) DESC
            """.formatted(query);

        if (pageable.isPaged()) {
            superQuery = superQuery + """
                LIMIT :limit
                OFFSET :offset
                """;
            params.addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        }


        return new QueryParamPair(superQuery, params);
    }

    private String createValuelessNodeFilter(String search, List<String> consents) {
        String rankQuery = "0 as rank";
        String rankWhere = "";
        if (StringUtils.hasLength(search)) {
            rankQuery = "ts_rank(searchable_fields, (phraseto_tsquery(:search)::text || ':*')::tsquery) as rank";
            rankWhere = "concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery AND";
        }
        String consentWhere = CollectionUtils.isEmpty(consents) ? "" : CONSENT_QUERY;
        // concept nodes that have no values and no min/max should not get returned by search
        return """
            SELECT
                concept_node.concept_node_id,
                %s
            FROM
                concept_node
                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
            WHERE
                %s
                %s
                (
                    continuous_min.value <> '' OR
                    continuous_max.value <> '' OR
                    categorical_values.value <> ''
                )
            """.formatted(rankQuery, rankWhere, consentWhere);
    }

    private List<String> createFacetFilter(Filter filter, MapSqlParameterSource params) {
        String consentWhere = CollectionUtils.isEmpty(filter.consents()) ? "" : CONSENT_QUERY;
        return filter.facets().stream()
            .collect(Collectors.groupingBy(Facet::category))
            .entrySet().stream()
            .map(facetsForCategory ->  {
                params
                    // The templating here is to namespace the params for each facet category in the query
                    .addValue("facets_for_category_%s".formatted(facetsForCategory.getKey()), facetsForCategory.getValue().stream().map(Facet::name).toList())
                    .addValue("category_%s".formatted(facetsForCategory.getKey()), facetsForCategory.getKey());
                String rankQuery = "0";
                String rankWhere = "";
                if (StringUtils.hasLength(filter.search())) {
                    rankQuery = "ts_rank(searchable_fields, (phraseto_tsquery(:search)::text || ':*')::tsquery)";
                    rankWhere = "concept_node.searchable_fields @@ (phraseto_tsquery(:search)::text || ':*')::tsquery AND";
                }
                return """
                (
                    SELECT
                        facet__concept_node.concept_node_id AS concept_node_id,
                        max(%s) as rank
                    FROM facet
                        LEFT JOIN facet__concept_node ON facet__concept_node.facet_id = facet.facet_id
                        JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = facet__concept_node.concept_node_id
                        LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                    WHERE
                        %s
                        %s
                        facet.name IN (:facets_for_category_%s ) AND facet_category.name = :category_%s
                    GROUP BY
                        facet__concept_node.concept_node_id
                )
                """.formatted(rankQuery, rankWhere, consentWhere, facetsForCategory.getKey(), facetsForCategory.getKey());
            })
            .toList();
    }

}
