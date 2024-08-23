package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.util.MapExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class FacetRepository {

    private final NamedParameterJdbcTemplate template;

    private final FacetMapper mapper;

    private final FacetQueryGenerator generator;

    @Autowired
    public FacetRepository(
        NamedParameterJdbcTemplate template, FacetQueryGenerator generator, FacetMapper mapper
    ) {
        this.template = template;
        this.generator = generator;
        this.mapper = mapper;
    }

    public List<FacetCategory> getFacets(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String innerSQL = generator.createFacetSQLAndPopulateParams(filter, params);
        // return a list of facets and the number of concepts associated with them
        String sql = """
            WITH facet_counts_q AS (
            %s
            )
            SELECT
                facet_category.name AS category_name,
                parent_facet.name AS parent_name,
                facet_counts_q.facet_count AS facet_count,
                facet_category.display as category_display,
                facet_category.description as category_description,
                facet.name, facet.display, facet.description,
                facet_meta_full_name.value AS full_name
            FROM
                facet
                LEFT JOIN facet_counts_q ON facet.facet_id = facet_counts_q.facet_id
                LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                LEFT JOIN facet as parent_facet ON facet.parent_id = parent_facet.facet_id
                LEFT JOIN facet_meta AS facet_meta_full_name ON facet.facet_id = facet_meta_full_name.facet_id AND facet_meta_full_name.KEY = 'full_name'
                
            """.formatted(innerSQL);

        return template.query(sql, params, new FacetCategoryExtractor());
    }

    public Optional<Facet> getFacet(String facetCategory, String facet) {
        String sql = """
            SELECT
                facet_category.name AS category,
                facet.name, facet.display, facet.description,
                facet_meta_full_name.value AS full_name
            FROM
                facet
                LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                LEFT JOIN facet_meta AS facet_meta_full_name ON facet.facet_id = facet_meta_full_name.facet_id AND facet_meta_full_name.KEY = 'full_name'
            WHERE
                facet.name = :facetName
                AND facet_category.name = :facetCategory
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("facetCategory", facetCategory)
            .addValue("facetName", facet);
        return template.query(sql, params, mapper).stream().findFirst();
    }

    public Map<String, String> getFacetMeta(String facetCategory, String facet) {
        String sql = """
            SELECT
                facet_meta.KEY, facet_meta.VALUE
            FROM
                facet_meta
                LEFT JOIN facet ON facet.facet_id = facet_meta.facet_id
                LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
            WHERE
                facet.name = :facetName
                AND facet_category.name = :facetCategory
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("facetCategory", facetCategory)
            .addValue("facetName", facet);
        return template.query(sql, params, new MapExtractor("KEY", "VALUE"));
    }
}
