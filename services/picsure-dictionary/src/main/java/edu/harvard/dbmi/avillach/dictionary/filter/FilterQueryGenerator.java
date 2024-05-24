package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.util.Pair;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class FilterQueryGenerator {

    public QueryParamPair generateFilterQuery(Filter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
            SELECT DISTINCT concept_node.concept_node_id
            FROM concept_node
                LEFT JOIN facet__concept_node ON facet__concept_node.concept_node_id = concept_node.concept_node_id
                LEFT JOIN facet ON facet.facet_id = facet__concept_node.facet_id
                LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
            WHERE TRUE
        """;
        if (!CollectionUtils.isEmpty(filter.facets())) {
            List<Object[]> facetTuples = filter.facets().stream()
                .map(f -> new Object[]{f.name(), f.category()})
                .toList();
            sql = sql + "AND (facet.name, facet_category.name) IN (:facets)\n";
            params.addValue("facets", facetTuples);
        }
        if (StringUtils.hasText(filter.search())) {
            sql = sql + "AND concept_node.concept_path LIKE :search\n";
            params.addValue("search", "%" + filter.search() + "%");
        }
        sql = sql + "ORDER BY concept_node.concept_node_id\n";
        if (pageable.isPaged()) {
            sql = sql + "LIMIT :limit OFFSET :offset";
            params.addValue("limit", pageable.toLimit().max())
                .addValue("offset", pageable.getOffset());
        }
        return new QueryParamPair(sql, params);
    }

    public QueryParamPair generateFilterQuery(Filter filter) {
        return generateFilterQuery(filter, Pageable.unpaged());
    }

}
