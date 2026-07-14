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
        List<Weight> weights = List.of(new Weight("TABLE_A.COLUMN", "A"), new Weight("TABLE_B.COLUMN", "B"));

        String actual = subject.createUpdate(weights);

        // Verify setweight structure with tier labels
        Assertions.assertTrue(
            actual.contains("setweight(to_tsvector('english', replace(coalesce(TABLE_A.COLUMN, ''), '_', ' ')), 'A')"),
            "Should contain setweight for TABLE_A.COLUMN with tier A"
        );
        Assertions.assertTrue(
            actual.contains("setweight(to_tsvector('english', replace(coalesce(TABLE_B.COLUMN, ''), '_', ' ')), 'B')"),
            "Should contain setweight for TABLE_B.COLUMN with tier B"
        );
        // Verify fields are concatenated with ||
        Assertions.assertTrue(actual.contains("||"), "Should concatenate tsvectors with ||");
        // Verify underscore replacement uses space (not slash)
        Assertions.assertTrue(actual.contains("'_', ' '"), "Should replace underscores with spaces");
        Assertions.assertFalse(actual.contains("'_', '/'"), "Should not replace underscores with slashes");
        // Verify output is a search_vector, not search_str
        Assertions.assertTrue(actual.contains("AS search_vector"), "Should output as search_vector");
        Assertions.assertFalse(actual.contains("concat("), "Should not use concat (old pattern)");
    }
}
