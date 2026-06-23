package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.dictionary.util.QueryUtility;
import edu.harvard.dbmi.avillach.dictionary.util.SchemaDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds dynamic SQL for facet count queries. Supports three modes: no facets selected, single category selected, and multi-category
 * selected. Each mode has search and no-search variants. Facet counts reflect how many displayable concepts match each facet, scoped by the
 * current search term and consent restrictions.
 */
@Component
public class FacetQueryGenerator {

    private final String fcnQueryable;
    private final String fcnQueryable2;

    private static final String CONSENT_QUERY = """
        dataset.dataset_id IN (
            SELECT
                consent.dataset_id
            FROM consent
                LEFT JOIN dataset ON dataset.dataset_id = consent.dataset_id
            WHERE
                concat(dataset.ref, '.', consent.consent_code) IN (:consents) OR
                (dataset.ref IN (:consents) AND consent.consent_code = '')
            UNION
            SELECT
                dataset_harmonization.harmonized_dataset_id
            FROM consent
                JOIN dataset_harmonization ON dataset_harmonization.source_dataset_id = consent.dataset_id
                LEFT JOIN dataset ON dataset.dataset_id = dataset_harmonization.source_dataset_id
            WHERE
                concat(dataset.ref, '.', consent.consent_code) IN (:consents) OR
                (dataset.ref IN (:consents) AND consent.consent_code = '')
        ) AND
        """;

    @Autowired
    public FacetQueryGenerator(SchemaDetector schemaDetector) {
        this.fcnQueryable = schemaDetector.fcnQueryableClause("fcn");
        this.fcnQueryable2 = schemaDetector.fcnQueryableClause("fcn2");
    }

    public String createFacetSQLAndPopulateParams(Filter filter, MapSqlParameterSource params) {
        Map<String, List<Facet>> groupedFacets =
            (filter.facets() == null ? Stream.<Facet>of() : filter.facets().stream()).collect(Collectors.groupingBy(Facet::category));
        String consentWhere = "";
        if (!CollectionUtils.isEmpty(filter.consents())) {
            params.addValue("consents", filter.consents());
            consentWhere = CONSENT_QUERY;
        }
        if (CollectionUtils.isEmpty(filter.facets())) {
            if (StringUtils.hasLength(filter.search())) {
                return createNoFacetSQLWithSearch(filter.search(), consentWhere, params);
            } else {
                return createNoFacetSQLNoSearch(params, consentWhere);
            }
        } else if (groupedFacets.size() == 1) {
            if (StringUtils.hasLength(filter.search())) {
                return createSingleCategorySQLWithSearch(filter.facets(), filter.search(), consentWhere, params);
            } else {
                return createSingleCategorySQLNoSearch(filter.facets(), consentWhere, params);
            }
        } else {
            if (StringUtils.hasLength(filter.search())) {
                return createMultiCategorySQLWithSearch(groupedFacets, filter.search(), consentWhere, params);
            } else {
                return createMultiCategorySQLNoSearch(groupedFacets, consentWhere, params);
            }
        }
    }

    /**
     * Returns independent count queries for multi-category facet requests, one per category block plus one for unselected categories. Each
     * query uses inline subqueries against base tables (no CTEs) for optimal planner cardinality estimation. Returns (facet_name,
     * category_name, facet_count) rows for merging in Java.
     */
    public List<QueryParamPair> createMultiCategoryCountBlocks(Filter filter) {
        Map<String, List<Facet>> groupedFacets =
            (filter.facets() == null ? Stream.<Facet>of() : filter.facets().stream()).collect(Collectors.groupingBy(Facet::category));
        MapSqlParameterSource params = new MapSqlParameterSource();
        String consentWhere = "";
        String consentJoins = "";
        if (!CollectionUtils.isEmpty(filter.consents())) {
            params.addValue("consents", filter.consents());
            consentWhere = CONSENT_QUERY;
            consentJoins = """
                LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""";
        }

        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(groupedFacets.keySet().stream().toList());
        boolean hasSearch = StringUtils.hasLength(filter.search());
        if (hasSearch) {
            params.addValue("search", filter.search());
        }

        List<String[]> allSelectedFacets =
            groupedFacets.values().stream().flatMap(List::stream).map(f -> new String[] {f.category(), f.name()}).toList();
        params.addValue("all_selected_facets", allSelectedFacets);
        params.addValue("num_categories", groupedFacets.size());
        params.addValue("num_other_categories", groupedFacets.size() - 1);
        params.addValue("all_selected_facet_categories", groupedFacets.keySet());
        groupedFacets.keySet().forEach(category -> params.addValue("facet_category_" + categoryKeys.get(category), category));

        String searchJoin = hasSearch ? "JOIN concept_node ON concept_node.concept_node_id = fcn2.concept_node_id" : "";
        String searchWhere = hasSearch ? "AND " + QueryUtility.SEARCH_WHERE : "";

        List<QueryParamPair> blocks = new ArrayList<>();

        // One block per selected category: inline IN subquery excludes this category
        for (String category : groupedFacets.keySet()) {
            String block = """
                SELECT facet.name AS facet_name, fc.name AS category_name, count(*) AS facet_count
                FROM facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                    %s
                WHERE %s
                    AND %s
                    fc.name = :facet_category_%s
                    AND fcn.concept_node_id IN (
                        SELECT fcn2.concept_node_id
                        FROM facet__concept_node fcn2
                            JOIN facet f2 ON f2.facet_id = fcn2.facet_id
                            JOIN facet_category fc2 ON fc2.facet_category_id = f2.facet_category_id
                            %s
                        WHERE %s
                            AND fc2.name != :facet_category_%s
                            AND (fc2.name, f2.name) IN (:all_selected_facets)
                            %s
                        GROUP BY fcn2.concept_node_id
                        HAVING count(DISTINCT fc2.name) = :num_other_categories
                    )
                GROUP BY facet.name, fc.name
                """.formatted(
                consentJoins, fcnQueryable, consentWhere, categoryKeys.get(category), searchJoin, fcnQueryable2, categoryKeys.get(category),
                searchWhere
            );
            blocks.add(new QueryParamPair(block, params));
        }

        // One block for unselected categories: inline IN subquery requires ALL categories
        String unselectedBlock = """
            SELECT facet.name AS facet_name, fc.name AS category_name, count(*) AS facet_count
            FROM facet
                JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                %s
            WHERE %s
                AND %s
                fc.name NOT IN (:all_selected_facet_categories)
                AND fcn.concept_node_id IN (
                    SELECT fcn2.concept_node_id
                    FROM facet__concept_node fcn2
                        JOIN facet f2 ON f2.facet_id = fcn2.facet_id
                        JOIN facet_category fc2 ON fc2.facet_category_id = f2.facet_category_id
                        %s
                    WHERE %s
                        AND (fc2.name, f2.name) IN (:all_selected_facets)
                        %s
                    GROUP BY fcn2.concept_node_id
                    HAVING count(DISTINCT fc2.name) = :num_categories
                )
            GROUP BY facet.name, fc.name
            """.formatted(consentJoins, fcnQueryable, consentWhere, searchJoin, fcnQueryable2, searchWhere);
        blocks.add(new QueryParamPair(unselectedBlock, params));

        return blocks;
    }

    private Map<String, String> createSQLSafeCategoryKeys(List<String> categories) {
        HashMap<String, String> keys = new HashMap<>();
        for (int i = 0; i < categories.size(); i++) {
            keys.put(categories.get(i), "cat_" + i);
        }
        return keys;
    }

    private String createMultiCategorySQLWithSearch(
        Map<String, List<Facet>> facets, String search, String consentWhere, MapSqlParameterSource params
    ) {
        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(facets.keySet().stream().toList());
        params.addValue("search", search);
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";

        List<String[]> allSelectedFacets =
            facets.values().stream().flatMap(List::stream).map(f -> new String[] {f.category(), f.name()}).toList();
        params.addValue("all_selected_facets", allSelectedFacets);
        params.addValue("num_categories", facets.size());
        params.addValue("num_other_categories", facets.size() - 1);
        params.addValue("all_selected_facet_categories", facets.keySet());
        facets.keySet().forEach(category -> params.addValue("facet_category_" + categoryKeys.get(category), category));

        // Each UNION block has an inline IN subquery with concept_node JOIN for search
        String selectedFacetsQuery = facets.keySet().stream()
            .map(
                category -> """
                    (
                        SELECT facet.facet_id, count(*) AS facet_count
                        FROM facet
                            JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                            JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                            %s
                        WHERE %s
                            AND %s
                            fc.name = :facet_category_%s
                            AND fcn.concept_node_id IN (
                                SELECT fcn2.concept_node_id
                                FROM facet__concept_node fcn2
                                    JOIN facet f2 ON f2.facet_id = fcn2.facet_id
                                    JOIN facet_category fc2 ON fc2.facet_category_id = f2.facet_category_id
                                    JOIN concept_node ON concept_node.concept_node_id = fcn2.concept_node_id
                                WHERE %s
                                    AND fc2.name != :facet_category_%s
                                    AND (fc2.name, f2.name) IN (:all_selected_facets)
                                    AND %s
                                GROUP BY fcn2.concept_node_id
                                HAVING count(DISTINCT fc2.name) = :num_other_categories
                            )
                        GROUP BY facet.facet_id
                        ORDER BY facet_count DESC
                    )""".formatted(
                    consentJoins, fcnQueryable, consentWhere, categoryKeys.get(category), fcnQueryable2, categoryKeys.get(category),
                    QueryUtility.SEARCH_WHERE
                )
            ).collect(Collectors.joining("\n\tUNION\n"));

        String unselectedFacetsQuery = """
            UNION
            (
                SELECT facet.facet_id, count(*) AS facet_count
                FROM facet
                    JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    %s
                WHERE %s
                    AND %s
                    fc.name NOT IN (:all_selected_facet_categories)
                    AND fcn.concept_node_id IN (
                        SELECT fcn2.concept_node_id
                        FROM facet__concept_node fcn2
                            JOIN facet f2 ON f2.facet_id = fcn2.facet_id
                            JOIN facet_category fc2 ON fc2.facet_category_id = f2.facet_category_id
                            JOIN concept_node ON concept_node.concept_node_id = fcn2.concept_node_id
                        WHERE %s
                            AND (fc2.name, f2.name) IN (:all_selected_facets)
                            AND %s
                        GROUP BY fcn2.concept_node_id
                        HAVING count(DISTINCT fc2.name) = :num_categories
                    )
                GROUP BY facet.facet_id
                ORDER BY facet_count DESC
            )""".formatted(consentJoins, fcnQueryable, consentWhere, fcnQueryable2, QueryUtility.SEARCH_WHERE);

        return selectedFacetsQuery + "\n" + unselectedFacetsQuery;
    }

    private String createMultiCategorySQLNoSearch(Map<String, List<Facet>> facets, String consentWhere, MapSqlParameterSource params) {
        Map<String, String> categoryKeys = createSQLSafeCategoryKeys(facets.keySet().stream().toList());
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";

        List<String[]> allSelectedFacets =
            facets.values().stream().flatMap(List::stream).map(f -> new String[] {f.category(), f.name()}).toList();
        params.addValue("all_selected_facets", allSelectedFacets);
        params.addValue("num_categories", facets.size());
        params.addValue("num_other_categories", facets.size() - 1);
        params.addValue("all_selected_facet_categories", facets.keySet());
        facets.keySet().forEach(category -> params.addValue("facet_category_" + categoryKeys.get(category), category));

        // Each UNION block has an inline IN subquery against base tables (no CTE).
        // This lets the planner see real pg_statistics and choose Hash Semi Join.
        String selectedFacetsQuery = facets.keySet().stream().map(category -> """
            (
                SELECT facet.facet_id, count(*) AS facet_count
                FROM facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                    %s
                WHERE %s
                    AND %s
                    fc.name = :facet_category_%s
                    AND fcn.concept_node_id IN (
                        SELECT fcn2.concept_node_id
                        FROM facet__concept_node fcn2
                            JOIN facet f2 ON f2.facet_id = fcn2.facet_id
                            JOIN facet_category fc2 ON fc2.facet_category_id = f2.facet_category_id
                        WHERE %s
                            AND fc2.name != :facet_category_%s
                            AND (fc2.name, f2.name) IN (:all_selected_facets)
                        GROUP BY fcn2.concept_node_id
                        HAVING count(DISTINCT fc2.name) = :num_other_categories
                    )
                GROUP BY facet.facet_id
                ORDER BY facet_count DESC
            )""".formatted(consentJoins, fcnQueryable, consentWhere, categoryKeys.get(category), fcnQueryable2, categoryKeys.get(category)))
            .collect(Collectors.joining("\n\tUNION\n"));

        String unselectedFacetsQuery = """
            UNION
            (
                SELECT facet.facet_id, count(*) AS facet_count
                FROM facet
                    JOIN facet_category fc ON fc.facet_category_id = facet.facet_category_id
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    %s
                WHERE %s
                    AND %s
                    fc.name NOT IN (:all_selected_facet_categories)
                    AND fcn.concept_node_id IN (
                        SELECT fcn2.concept_node_id
                        FROM facet__concept_node fcn2
                            JOIN facet f2 ON f2.facet_id = fcn2.facet_id
                            JOIN facet_category fc2 ON fc2.facet_category_id = f2.facet_category_id
                        WHERE %s
                            AND (fc2.name, f2.name) IN (:all_selected_facets)
                        GROUP BY fcn2.concept_node_id
                        HAVING count(DISTINCT fc2.name) = :num_categories
                    )
                GROUP BY facet.facet_id
                ORDER BY facet_count DESC
            )""".formatted(consentJoins, fcnQueryable, consentWhere, fcnQueryable2);

        return selectedFacetsQuery + "\n" + unselectedFacetsQuery;
    }

    private String createSingleCategorySQLWithSearch(List<Facet> facets, String search, String consentWhere, MapSqlParameterSource params) {
        params.addValue("facet_category_name", facets.getFirst().category());
        params.addValue("facets", facets.stream().map(Facet::name).toList());
        params.addValue("search", search);
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";
        // Part 1: facets in the matched category that are displayable + match search
        // Part 2: facets from other categories for concepts matching selected facets + search
        return """
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                    LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                WHERE
                    %s
                    AND %s
                    fc.name = :facet_category_name
                    AND %s
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            UNION
            (
                WITH matching_concepts AS (
                    SELECT
                        DISTINCT(fcn.concept_node_id) AS concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                        JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                    WHERE
                        %s
                        AND fc.name = :facet_category_name
                        AND facet.name IN (:facets)
                        AND %s
                )
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    %s
                    JOIN matching_concepts ON fcn.concept_node_id = matching_concepts.concept_node_id
                WHERE
                    %s
                    AND %s
                    fc.name <> :facet_category_name
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(
            fcnQueryable, consentWhere, QueryUtility.SEARCH_WHERE, fcnQueryable, QueryUtility.SEARCH_WHERE, consentJoins, fcnQueryable,
            consentWhere
        );
    }

    private String createSingleCategorySQLNoSearch(List<Facet> facets, String consentWhere, MapSqlParameterSource params) {
        params.addValue("facet_category_name", facets.getFirst().category());
        params.addValue("facets", facets.stream().map(Facet::name).toList());
        String consentJoins = StringUtils.hasLength(consentWhere) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";
        // return all the facets in the matched category that are displayable
        // UNION
        // all the facets from other categories that match concepts that match selected facets from this category
        return """
            (
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    %s
                WHERE
                    %s
                    AND %s
                    fc.name = :facet_category_name
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            UNION
            (
                WITH matching_concepts AS (
                    SELECT
                        DISTINCT(fcn.concept_node_id) AS concept_node_id
                    FROM
                        facet
                        JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                        JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    WHERE
                        %s
                        AND fc.name = :facet_category_name
                        AND facet.name IN (:facets)
                )
                SELECT
                    facet.facet_id, count(*) as facet_count
                FROM
                    facet
                    JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                    JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                    %s
                    JOIN matching_concepts ON fcn.concept_node_id = matching_concepts.concept_node_id
                WHERE
                    %s
                    AND %s
                    fc.name <> :facet_category_name
                GROUP BY
                    facet.facet_id
                ORDER BY
                    facet_count DESC
            )
            """.formatted(consentJoins, fcnQueryable, consentWhere, fcnQueryable, consentJoins, fcnQueryable, consentWhere);
    }

    private String createNoFacetSQLWithSearch(String search, String consentWhere, MapSqlParameterSource params) {
        // return all the facets that match concepts that
        // match search
        // are displayable
        params.addValue("search", search);
        String datasetJoin = StringUtils.hasLength(consentWhere) ? "LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id" : "";
        return """
            SELECT
                facet.facet_id, count(*) as facet_count
            FROM
                facet
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
                %s
            WHERE
                %s
                AND %s
                %s
            GROUP BY
                facet.facet_id
            ORDER BY
                facet_count DESC
            """.formatted(datasetJoin, fcnQueryable, consentWhere, QueryUtility.SEARCH_WHERE);

    }

    private String createNoFacetSQLNoSearch(MapSqlParameterSource params, String consents) {
        String consentJoins = StringUtils.hasLength(consents) ? """
            LEFT JOIN concept_node ON concept_node.concept_node_id = fcn.concept_node_id
            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id""" : "";
        String whereClause = StringUtils.hasLength(consents) ? consents.strip().replaceAll("\\s+AND\\s*$", "") : "TRUE";
        return """
            SELECT
                facet.facet_id, count(*) as facet_count
            FROM
                facet
                JOIN facet__concept_node fcn ON fcn.facet_id = facet.facet_id
                JOIN facet_category fc on fc.facet_category_id = facet.facet_category_id
                %s
            WHERE
                %s
                AND %s
            GROUP BY
                facet.facet_id
            ORDER BY
                facet_count DESC
            """.formatted(consentJoins, fcnQueryable, whereClause);
    }
}
