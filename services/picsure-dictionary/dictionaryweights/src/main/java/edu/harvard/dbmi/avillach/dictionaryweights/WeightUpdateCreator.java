package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Generates the SQL UPDATE statement that populates the searchable_fields tsvector
 * column on concept_node. The tsvector is built from weighted fields (display name,
 * concept path, dataset info, parent/grandparent names) and a filtered subset of
 * concept_node_meta values.
 *
 * <p>Meta values are filtered to a whitelist of searchable keys (description, values,
 * derived_values, comment, domain, question) to avoid indexing non-searchable content
 * like DRS URIs or numeric identifiers. The 'values' key is capped at 60K characters
 * to handle imaging datasets with very large categorical value sets.</p>
 */
@Service
public class WeightUpdateCreator {

    private static final Logger LOG = LoggerFactory.getLogger(WeightUpdateCreator.class);

    /**
     * Builds the UPDATE statement from the given weight configuration. Each weight entry
     * specifies a field name and a repeat count — higher weights mean the field's text
     * appears more times in the concat, increasing its tsvector rank contribution.
     */
    public String createUpdate(List<Weight> weights) {
        LOG.info("Turning {} weights into a big concat query", weights.size());
        String searchableFields = weights.stream()
            .flatMap(this::expand)
            .collect(Collectors.joining(", ' ',\n            "));
        return """
            UPDATE concept_node
            SET SEARCHABLE_FIELDS = to_tsvector(replace(data_table.search_str, '_', '/'))
            FROM
            (
                SELECT
                    concat(
                        %s
                    ) AS search_str,
                    concept_node.concept_node_id AS search_key
                FROM
                    concept_node
                    LEFT JOIN
                    (
                        SELECT
                            concept_node_id AS id,
                            string_agg(DISTINCT safe_value, ' ') AS values
                        FROM (
                            SELECT concept_node_id, value AS safe_value
                            FROM concept_node_meta
                            WHERE value <> ''
                              AND key IN ('description','derived_values','comment','domain','Question','question')
                            UNION ALL
                            SELECT concept_node_id, left(value, 60000) AS safe_value
                            FROM concept_node_meta
                            WHERE value <> '' AND key = 'values'
                        ) AS filtered_meta
                        GROUP BY
                            concept_node_id
                    ) AS concept_node_meta_str ON concept_node_meta_str.id = concept_node.concept_node_id
                    LEFT JOIN dataset AS study ON concept_node.dataset_id = study.dataset_id
                    LEFT JOIN concept_node AS parent ON concept_node.parent_id = parent.concept_node_id
                    LEFT JOIN concept_node AS grandparent ON parent.parent_id = grandparent.concept_node_id
                    LEFT JOIN (
                        SELECT
                            dataset_node.name AS NAME,
                            dataset_node.display AS FULL_NAME,
                            dataset_node.concept_path AS CONCEPT_PATH,
                            description_concept_node_meta.VALUE AS DESCRIPTION
                        FROM
                            concept_node AS dataset_node
                            JOIN concept_node_meta AS description_concept_node_meta ON description_concept_node_meta.KEY = 'description' AND description_concept_node_meta.concept_node_id = dataset_node.concept_node_id
                    ----------------------------------------------------------------------------------------------------
                    -- This join is saying "find me the concept node that shares the first two nodes of this concept
                    -- and then stops". So for /a/b/c/d/e/f/, it finds concept /a/b/
                    ----------------------------------------------------------------------------------------------------
                    ) AS dataset ON dataset.concept_path = REGEXP_REPLACE(concept_node.concept_path, '(^\\\\[^\\\\]*\\\\[^\\\\]*\\\\)(.*$)', '\\1')
            ) AS data_table
            WHERE concept_node.concept_node_id = data_table.search_key;
            """.formatted(searchableFields);
    }

    private Stream<String> expand(Weight weight) {
        return IntStream.range(0, weight.weight()).boxed()
            .map(i -> weight.key());
    }
}
