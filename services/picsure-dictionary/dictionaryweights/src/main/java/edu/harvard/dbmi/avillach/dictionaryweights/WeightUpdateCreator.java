package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class WeightUpdateCreator {


    private static final Logger LOG = LoggerFactory.getLogger(WeightUpdateCreator.class);

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
                            concept_node.concept_node_id AS id, left(string_agg(value, ' '), 20000) AS values
                        FROM
                            concept_node
                            join concept_node_meta on concept_node.concept_node_id = concept_node_meta.concept_node_id
                        GROUP BY
                            concept_node.concept_node_id
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
