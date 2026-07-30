package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptFilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.concept.ConceptRowMapper;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

import static edu.harvard.dbmi.avillach.dictionary.util.QueryUtility.ALLOW_FILTERING_Q;

/**
 * The search-prototype-compatible ranking query. It differs from {@link edu.harvard.dbmi.avillach.dictionary.concept.ConceptRepository}
 * only in its filter clause -- it takes a caller-built tsquery rather than the sanitized prefix search -- and returns the same
 * {@link Concept} projection so a hit on /search and a hit on /concepts are the same object.
 */
@Repository
public class LegacySearchRepository {

    private final ConceptFilterQueryGenerator filterGen;
    private final NamedParameterJdbcTemplate template;
    private final List<String> disallowedMetaFields;
    private final ConceptRowMapper conceptRowMapper;

    @Autowired
    public LegacySearchRepository(
        ConceptFilterQueryGenerator filterGen, NamedParameterJdbcTemplate template,
        @Value("${filtering.unfilterable_concepts}") List<String> disallowedMetaFields, ConceptRowMapper conceptRowMapper
    ) {
        this.filterGen = filterGen;
        this.template = template;
        this.disallowedMetaFields = disallowedMetaFields;
        this.conceptRowMapper = conceptRowMapper;
    }

    public List<Concept> getLegacySearchResults(Filter filter, Pageable pageable) {
        QueryParamPair filterQ = filterGen.generateLegacyFilterQuery(filter, pageable);
        String sql = ALLOW_FILTERING_Q + ", " + filterQ.query()
            + """
                SELECT
                    concept_node.*,
                    ds.REF                                            AS dataset,
                    ds.abbreviation                                   AS studyAcronym,
                    continuous_min.VALUE                              AS min,
                    continuous_max.VALUE                              AS max,
                    categorical_values.VALUE                          AS values,
                    coalesce(allow_filtering.allowFiltering, TRUE)     AS allowFiltering,
                    meta_description.VALUE                            AS description
                FROM concept_node
                    INNER JOIN concepts_filtered_sorted ON concepts_filtered_sorted.concept_node_id = concept_node.concept_node_id
                    LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                    LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                    LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                    LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                    LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    LEFT JOIN allow_filtering ON concept_node.concept_node_id = allow_filtering.concept_node_id
                ORDER BY concepts_filtered_sorted.rank DESC, concept_node.concept_node_id ASC
                """;
        MapSqlParameterSource params = filterQ.params().addValue("disallowed_meta_keys", disallowedMetaFields);

        return template.query(sql, params, conceptRowMapper);
    }

}
