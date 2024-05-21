package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.util.Pair;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class FilterQueryGenerator {

    protected static final String FACET_QUERY = """
        
        AND facet__concept_node.facet_id IN (
            SELECT facet.facet_id
            FROM facet
                LEFT JOIN facet_category ON facet.facet_category_id = facet.facet_category_id
            WHERE (facet.name, facet_category.name) IN (:facets)
        )
        
        """;
    protected static final String SEARCH_QUERY = """
        
        AND concept_node.concept_path LIKE :search
        
        """;

    public Pair<String, MapSqlParameterSource> generateFilterQuery(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "";
        if (!CollectionUtils.isEmpty(filter.facets())) {
            List<Object[]> facetTuples = filter.facets().stream()
                .map(f -> new Object[]{f.name(), f.category()})
                .toList();
            params.addValue("facets", facetTuples);
            sql = sql + FACET_QUERY;
        }
        if (StringUtils.hasLength(filter.search())) {
            params.addValue("search", "%" + filter.search() + "%");
            sql = sql + SEARCH_QUERY;
        }
        return new Pair<>(sql, params);
    }

}
