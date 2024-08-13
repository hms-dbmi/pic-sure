package edu.harvard.dbmi.avillach.dictionaryweights;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class WeightUpdateCreatorTest {
    @Autowired
    WeightUpdateCreator subject;

    @Test
    void shouldCreateQueryForWeights() {
        List<Weight> weights = List.of(new Weight("TABLE_A.COLUMN", 2), new Weight("TABLE_B.COLUMN", 1));

        String actual = subject.createUpdate(weights);
        String expected = """
            UPDATE concept_node
            SET SEARCHABLE_FIELDS = data_table.search_str
            FROM
            (
                SELECT
                    concat(
                        TABLE_A.COLUMN, ' ',
                        TABLE_A.COLUMN, ' ',
                        TABLE_B.COLUMN
                    ) AS search_str,
                    concept_node.concept_node_id AS search_key
                FROM
                    concept_node
                    LEFT JOIN
                    (
                        SELECT
                            concept_node.concept_node_id AS id, string_agg(value, ' ') AS values
                        FROM
                            concept_node
                            join concept_node_meta on concept_node.concept_node_id = concept_node_meta.concept_node_id
                        GROUP BY
                            concept_node.concept_node_id
                    ) AS inner_q ON inner_q.id = concept_node.concept_node_id
            ) AS data_table
            WHERE concept_node.concept_node_id = data_table.search_key;
            """;

        Assertions.assertEquals(expected.trim(), actual.trim());
    }
}