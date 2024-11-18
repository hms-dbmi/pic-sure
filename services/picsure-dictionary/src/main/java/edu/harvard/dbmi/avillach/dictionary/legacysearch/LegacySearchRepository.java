package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptFilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

import static edu.harvard.dbmi.avillach.dictionary.util.QueryUtility.ALLOW_FILTERING_Q;

@Repository
public class LegacySearchRepository {

    private final ConceptFilterQueryGenerator filterGen;
    private final NamedParameterJdbcTemplate template;
    private final List<String> disallowedMetaFields;
    private final SearchResultRowMapper searchResultRowMapper;

    @Autowired
    public LegacySearchRepository(
        ConceptFilterQueryGenerator filterGen, NamedParameterJdbcTemplate template,
        @Value("${filtering.unfilterable_concepts}") List<String> disallowedMetaFields, SearchResultRowMapper searchResultRowMapper
    ) {
        this.filterGen = filterGen;
        this.template = template;
        this.disallowedMetaFields = disallowedMetaFields;
        this.searchResultRowMapper = searchResultRowMapper;
    }

    public List<SearchResult> getLegacySearchResults(Filter filter, Pageable pageable) {
        QueryParamPair filterQ = filterGen.generateFilterQuery(filter, pageable);
        String sql = ALLOW_FILTERING_Q + ", " + filterQ.query() + """
                SELECT concept_node.concept_path  AS conceptPath,
                   concept_node.display           AS display,
                   concept_node.name              AS name,
                   concept_node.concept_type      AS conceptType,
                   ds.REF                         as dataset,
                   ds.abbreviation                AS studyAcronym,
                   ds.full_name                   as dsFullName,
                   continuous_min.VALUE           as min,
                   continuous_max.VALUE           as max,
                   categorical_values.VALUE       as values,
                   allow_filtering.allowFiltering AS allowFiltering,
                   meta_description.VALUE         AS description,
                   stigmatized.value              AS stigmatized,
                   parent.name                    AS parentName,
                   parent.display                 AS parentDisplay
            FROM concept_node
                     INNER JOIN concepts_filtered_sorted ON concepts_filtered_sorted.concept_node_id = concept_node.concept_node_id
                     LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                     LEFT JOIN concept_node_meta AS meta_description
                               ON concept_node.concept_node_id = meta_description.concept_node_id AND
                                  meta_description.KEY = 'description'
                     LEFT JOIN concept_node_meta AS continuous_min
                               ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                     LEFT JOIN concept_node_meta AS continuous_max
                               ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                     LEFT JOIN concept_node_meta AS categorical_values
                               ON concept_node.concept_node_id = categorical_values.concept_node_id AND
                                  categorical_values.KEY = 'values'
                     LEFT JOIN concept_node_meta AS stigmatized ON concept_node.concept_node_id = stigmatized.concept_node_id AND
                                                                   stigmatized.KEY = 'stigmatized'
                     LEFT JOIN concept_node AS parent ON parent.concept_node_id = concept_node.parent_id
                     LEFT JOIN allow_filtering ON concept_node.concept_node_id = allow_filtering.concept_node_id
            ORDER BY concepts_filtered_sorted.rank DESC, concept_node.concept_node_id ASC
            """;
        MapSqlParameterSource params = filterQ.params().addValue("disallowed_meta_keys", disallowedMetaFields);

        return template.query(sql, params, searchResultRowMapper);
    }

}
