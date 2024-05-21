package edu.harvard.dbmi.avillach.dictionary.concept.persistence;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.FilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

@Repository
public class ConceptJDBCRepository {

    private final NamedParameterJdbcTemplate template;

    private final ConceptRowMapper mapper;

    private final FilterQueryGenerator filterGen;

    @Autowired
    public ConceptJDBCRepository(
        NamedParameterJdbcTemplate template, ConceptRowMapper mapper, FilterQueryGenerator filterGen
    ) {
        this.template = template;
        this.mapper = mapper;
        this.filterGen = filterGen;
    }


    public List<Concept> getConcepts(Filter filter) {
        String sql = """
            SELECT
                concept_node.*,
                ds.REF as dataset,
                continuous_min.VALUE as min, continuous_max.VALUE as max,
                categorical_values.VALUE as values
            FROM concept_node
                LEFT JOIN facet__concept_node ON facet__concept_node.concept_node_id = concept_node.concept_node_id
                LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'MIN'
                LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'MAX'
                LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'VALUES'
            WHERE TRUE
            """;
        Pair<String, MapSqlParameterSource> filterQ = filterGen.generateFilterQuery(filter);
        sql = sql + filterQ.first();
        MapSqlParameterSource params = filterQ.second();

        return template.query(sql, params, mapper);
    }
}
