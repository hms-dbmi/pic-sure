package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.dictionary.util.MapExtractor;
import edu.harvard.dbmi.avillach.dictionary.util.QueryUtility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;


@Repository
public class ConceptRepository {

    private final NamedParameterJdbcTemplate template;
    private final ConceptRowMapper mapper;
    private final ConceptFilterQueryGenerator filterGen;
    private final ConceptMetaExtractor conceptMetaExtractor;
    private final ConceptResultSetExtractor conceptResultSetExtractor;
    private final ConceptRowWithMetaMapper conceptRowWithMetaMapper;
    private final List<String> disallowedMetaFields;

    @Autowired
    public ConceptRepository(
        NamedParameterJdbcTemplate template, ConceptRowMapper mapper, ConceptFilterQueryGenerator filterGen,
        ConceptMetaExtractor conceptMetaExtractor, ConceptResultSetExtractor conceptResultSetExtractor,
        ConceptRowWithMetaMapper conceptRowWithMetaMapper, @Value("${filtering.unfilterable_concepts}") List<String> disallowedMetaFields
    ) {
        this.template = template;
        this.mapper = mapper;
        this.filterGen = filterGen;
        this.conceptMetaExtractor = conceptMetaExtractor;
        this.conceptResultSetExtractor = conceptResultSetExtractor;
        this.conceptRowWithMetaMapper = conceptRowWithMetaMapper;
        this.disallowedMetaFields = disallowedMetaFields;
    }


    public List<Concept> getConcepts(Filter filter, Pageable pageable) {
        QueryParamPair filterQ = filterGen.generateFilterQuery(filter, pageable);
        String sql = QueryUtility.ALLOW_FILTERING_Q + ", " + filterQ.query()
            + """
                SELECT
                    concept_node.*,
                    ds.REF as dataset,
                    ds.abbreviation AS studyAcronym,
                    continuous_min.VALUE as min, continuous_max.VALUE as max,
                    categorical_values.VALUE as values,
                    coalesce(allow_filtering.allowFiltering, TRUE) AS allowFiltering,
                    meta_description.VALUE AS description
                FROM
                    concept_node
                    INNER JOIN concepts_filtered_sorted ON concepts_filtered_sorted.concept_node_id = concept_node.concept_node_id
                    LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                    LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                    LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                    LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                    LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    LEFT JOIN allow_filtering ON concept_node.concept_node_id = allow_filtering.concept_node_id
                ORDER BY
                    concepts_filtered_sorted.rank DESC, concept_node.concept_node_id ASC
                """;
        MapSqlParameterSource params = filterQ.params().addValue("disallowed_meta_keys", disallowedMetaFields);

        return template.query(sql, params, mapper);
    }

    public long countConcepts(Filter filter) {
        QueryParamPair pair = filterGen.generateFilterQuery(filter, Pageable.unpaged());
        String sql = "WITH " + pair.query() + " SELECT count(*) FROM concepts_filtered_sorted;";
        Long count = template.queryForObject(sql, pair.params(), Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Concept> getConcept(String dataset, String conceptPath) {
        String sql = QueryUtility.ALLOW_FILTERING_Q
            + """
                SELECT
                    concept_node.*,
                    ds.REF as dataset,
                    ds.abbreviation AS studyAcronym,
                    continuous_min.VALUE as min, continuous_max.VALUE as max,
                    categorical_values.VALUE as values,
                    coalesce(allow_filtering.allowFiltering, TRUE) AS allowFiltering,
                    meta_description.VALUE AS description
                FROM
                    concept_node
                    LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                    LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                    LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                    LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                    LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                    LEFT JOIN allow_filtering ON concept_node.concept_node_id = allow_filtering.concept_node_id
                WHERE
                    concept_node.concept_path = :conceptPath
                    AND ds.REF = :dataset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("conceptPath", conceptPath).addValue("dataset", dataset)
            .addValue("disallowed_meta_keys", disallowedMetaFields);
        return template.query(sql, params, mapper).stream().findFirst();
    }

    public Map<String, String> getConceptMeta(String dataset, String conceptPath) {
        String sql = """
            SELECT
                concept_node_meta.KEY, concept_node_meta.VALUE
            FROM
                concept_node
                LEFT JOIN concept_node_meta ON concept_node.concept_node_id = concept_node_meta.concept_node_id
                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
            WHERE
                concept_node.concept_path = :conceptPath
                AND dataset.REF = :dataset
            """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("conceptPath", conceptPath).addValue("dataset", dataset);
        return template.query(sql, params, new MapExtractor("KEY", "VALUE"));
    }

    public Map<Concept, Map<String, String>> getConceptMetaForConcepts(List<Concept> concepts) {
        String sql = """
            SELECT
                concept_node_meta.KEY, concept_node_meta.VALUE,
                concept_node.CONCEPT_PATH AS concept_path, dataset.REF AS dataset_name
            FROM
                concept_node
                LEFT JOIN concept_node_meta ON concept_node.concept_node_id = concept_node_meta.concept_node_id
                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
            WHERE
                (concept_node.CONCEPT_PATH, dataset.REF) IN (:pairs)
            ORDER BY concept_node.CONCEPT_PATH, dataset.REF
            """;
        List<String[]> pairs = concepts.stream().map(c -> new String[] {c.conceptPath(), c.dataset()}).toList();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("pairs", pairs);

        return template.query(sql, params, conceptMetaExtractor);

    }

    public Optional<Concept> getConceptTree(String dataset, String conceptPath, int depth) {
        // if this is for the absolute root of this dataset's ontology, conceptPath
        // will be empty, and we should just find the parentless concept instead.
        String rootConceptMatch =
            StringUtils.hasLength(conceptPath) ? "concept_node.CONCEPT_PATH = :path" : "concept_node.PARENT_ID IS NULL";
        String sql = QueryUtility.ALLOW_FILTERING_Q
            + """
                    , core_query AS (
                        WITH RECURSIVE nodes AS (
                            SELECT
                                concept_node_id, parent_id, 0 AS depth
                            FROM
                                concept_node
                                LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                            WHERE
                                %s
                                AND dataset.REF = :dataset
                        UNION
                            SELECT
                                child_nodes.concept_node_id, child_nodes.parent_id, parent_node.depth+ 1
                            FROM
                                concept_node child_nodes
                                INNER JOIN nodes parent_node ON child_nodes.parent_id = parent_node.concept_node_id
                                LEFT JOIN dataset ON child_nodes.dataset_id = dataset.dataset_id
                        )
                        SELECT
                            depth, child_nodes.concept_node_id
                        FROM
                            nodes parent_node
                            INNER JOIN concept_node child_nodes ON child_nodes.parent_id = parent_node.concept_node_id
                        WHERE
                            depth < :depth
                        UNION
                        SELECT
                            0 as depth, concept_node.concept_node_id
                        FROM
                            concept_node
                            LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                        WHERE
                            %s
                            AND dataset.REF = :dataset
                        UNION
                        SELECT
                            -1 as depth, concept_node.concept_node_id
                        FROM
                            concept_node
                        WHERE
                            concept_node.concept_node_id = (
                                SELECT
                                    parent_id
                                FROM
                                    concept_node
                                    LEFT JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
                                WHERE
                                    %s
                                    AND dataset.REF = :dataset
                            )
                        ORDER BY depth ASC
                    )
                    SELECT
                        concept_node.*,
                        ds.REF AS dataset,
                        ds.abbreviation AS studyAcronym,
                        continuous_min.VALUE AS min, continuous_max.VALUE AS max,
                        categorical_values.VALUE AS values,
                        meta_description.VALUE AS description,
                        coalesce(allow_filtering.allowFiltering, TRUE) AS allowFiltering,
                        core_query.depth AS depth
                    FROM
                        concept_node
                        INNER JOIN core_query ON concept_node.concept_node_id = core_query.concept_node_id
                        LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                        LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                        LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                        LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                        LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                        LEFT JOIN allow_filtering ON concept_node.concept_node_id = allow_filtering.concept_node_id
                    ORDER BY LENGTH(concept_node.concept_path)
                """
                .formatted(rootConceptMatch, rootConceptMatch, rootConceptMatch);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("path", conceptPath).addValue("dataset", dataset)
            .addValue("depth", depth).addValue("disallowed_meta_keys", disallowedMetaFields);

        if (depth < 0) {
            return Optional.empty();
        }

        return Optional.ofNullable(template.query(sql, params, conceptResultSetExtractor));

    }


    public List<Concept> getConceptsByPathWithMetadata(List<String> conceptPaths) {
        String sql = QueryUtility.ALLOW_FILTERING_Q + ", "
            + """
                filtered_concepts AS (
                     SELECT
                        concept_node.*
                     FROM
                         concept_node
                     WHERE
                         concept_path IN (:conceptPaths)
                 ),
                 aggregated_meta AS (
                     SELECT
                         concept_node_meta.concept_node_id,
                         json_agg(json_build_object('key', concept_node_meta.key, 'value', concept_node_meta.value)) AS metadata
                     FROM
                         concept_node_meta
                     WHERE
                         concept_node_meta.concept_node_id IN (
                             SELECT concept_node_id FROM filtered_concepts
                         )
                     GROUP BY
                         concept_node_meta.concept_node_id
                 )
                 SELECT
                     concept_node.*,
                     ds.REF as dataset,
                     ds.abbreviation AS studyAcronym,
                     continuous_min.VALUE as min, continuous_max.VALUE as max,
                     categorical_values.VALUE as values,
                     coalesce(allow_filtering.allowFiltering, TRUE) AS allowFiltering,
                     meta_description.VALUE AS description,
                     aggregated_meta.metadata AS metadata
                 FROM
                     filtered_concepts as concept_node
                     LEFT JOIN dataset AS ds ON concept_node.dataset_id = ds.dataset_id
                     LEFT JOIN concept_node_meta AS meta_description ON concept_node.concept_node_id = meta_description.concept_node_id AND meta_description.KEY = 'description'
                     LEFT JOIN concept_node_meta AS continuous_min ON concept_node.concept_node_id = continuous_min.concept_node_id AND continuous_min.KEY = 'min'
                     LEFT JOIN concept_node_meta AS continuous_max ON concept_node.concept_node_id = continuous_max.concept_node_id AND continuous_max.KEY = 'max'
                     LEFT JOIN concept_node_meta AS categorical_values ON concept_node.concept_node_id = categorical_values.concept_node_id AND categorical_values.KEY = 'values'
                     LEFT JOIN allow_filtering ON concept_node.concept_node_id = allow_filtering.concept_node_id
                     LEFT JOIN aggregated_meta ON concept_node.concept_node_id = aggregated_meta.concept_node_id
                """;

        MapSqlParameterSource params =
            new MapSqlParameterSource().addValue("conceptPaths", conceptPaths).addValue("disallowed_meta_keys", disallowedMetaFields);
        return template.query(sql, params, conceptRowWithMetaMapper);
    }
}
