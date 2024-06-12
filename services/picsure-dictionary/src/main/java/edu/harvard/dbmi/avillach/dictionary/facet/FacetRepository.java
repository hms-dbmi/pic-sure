package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.FilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.dictionary.util.MapExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class FacetRepository {

    private final NamedParameterJdbcTemplate template;

    private final FilterQueryGenerator generator;

    private final FacetMapper mapper;

    @Autowired
    public FacetRepository(
        NamedParameterJdbcTemplate template, FilterQueryGenerator generator, FacetMapper mapper
    ) {
        this.template = template;
        this.generator = generator;
        this.mapper = mapper;
    }

    public List<FacetCategory> getFacets(Filter filter) {
        QueryParamPair pair = generator.generateFilterQuery(filter, Pageable.unpaged());
        String sql = """
            SELECT
                facet_category.name AS category_name,
                parent_facet.name AS parent_name,
                facet_count_q.facet_count AS facet_count,
                facet_category.display as category_display,
                facet_category.description as category_description,
                facet.name, facet.display, facet.description
            FROM
                facet
                LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                LEFT JOIN facet as parent_facet ON facet.parent_id = parent_facet.facet_id
                LEFT JOIN (
                    SELECT
                        count(*) as facet_count, inner_facet_q.facet_id AS inner_facet_id
                    FROM
                        facet AS inner_facet_q
                        JOIN facet__concept_node AS inner_facet__concept_node_q ON inner_facet__concept_node_q.facet_id = inner_facet_q.facet_id
                    WHERE
                        inner_facet__concept_node_q.concept_node_id IN (%s)
                    GROUP BY inner_facet_q.facet_id
                ) AS facet_count_q ON facet_count_q.inner_facet_id = facet.facet_id
            """.formatted(pair.query());

        return template.query(sql, pair.params(), new FacetCategoryExtractor());
    }

    public Optional<Facet> getFacet(String facetCategory, String facet) {
        String sql = """
            SELECT
                facet_category.name AS category,
                facet.name, facet.display, facet.description
            FROM
                facet
                LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
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
