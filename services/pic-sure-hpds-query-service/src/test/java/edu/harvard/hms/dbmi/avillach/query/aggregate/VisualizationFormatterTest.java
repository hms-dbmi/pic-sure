package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualizationFormatterTest {

    private final VisualizationFormatter fmt = new VisualizationFormatter();

    @Test
    void skipsConsentAxisKeys() {
        assertThat(fmt.skipKey("\\_consents\\")).isTrue();
        assertThat(fmt.skipKey("\\_harmonized_consent\\")).isTrue();
        assertThat(fmt.skipKey("\\_topmed_consents\\")).isTrue();
        assertThat(fmt.skipKey("\\_parent_consents\\")).isTrue();
        assertThat(fmt.skipKey("\\demographics\\SEX\\")).isFalse();
    }

    private static final String GROUPED = VisualizationFormatter.GROUPED_KEY;

    private Map<String, Object> axis(Object... labelsAndCounts) {
        Map<String, Object> axis = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndCounts.length; i += 2) {
            axis.put((String) labelsAndCounts[i], labelsAndCounts[i + 1]);
        }
        return axis;
    }

    @Test
    void foldsCategoriesBelowTheThresholdIntoTheGroupedBucket() {
        Map<String, Object> out = fmt.processResults(axis("White", 140, "Black", 55, "Asian", 22, "Native Hawaiian", 1), 10);
        assertThat(out).containsOnlyKeys("Asian", "Black", "White", GROUPED).containsEntry(GROUPED, 1);
    }

    @Test
    void groupedBucketIsAlwaysPresentEvenWhenEmpty() {
        assertThat(fmt.processResults(axis("Female", 212, "Male", 190), 10)).containsEntry(GROUPED, 0);
    }

    @Test
    void aCategoryWithZeroAndOneToNinePatientsProduceTheSameLabels() {
        for (int small = 0; small < 10; small++) {
            Map<String, Object> out = fmt.processResults(axis("Female", 212, "Male", 187, "Unknown", small), 10);
            assertThat(out.keySet()).containsExactly("Female", "Male", GROUPED);
        }
    }

    @Test
    void keepsTheSevenLargestNamedCategoriesAndFoldsTheRest() {
        Map<String, Object> axis = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            axis.put("cat" + i, 10 + i);
        }
        axis.put("rare", 3);
        Map<String, Object> out = fmt.processResults(axis, 10);
        assertThat(out).hasSize(8).containsKeys("cat3", "cat9").doesNotContainKeys("cat0", "cat1", "cat2", "rare")
            .containsEntry(GROUPED, 10 + 11 + 12 + 3);
    }

    @Test
    void aRealCategoryNamedOtherIsKeptSeparately() {
        Map<String, Object> out = fmt.processResults(axis("Yes", 80, "No", 60, "Other", 12, "Refused", 2), 10);
        assertThat(out).containsEntry("Other", 12).containsEntry(GROUPED, 2);
    }

    @Test
    void namedCategoriesAreOrderedByLabelWithTheGroupedBucketLast() {
        Map<String, Object> out = fmt.processResults(axis("b", 50, "c", 11, "a", 30), 10);
        assertThat(out.keySet()).containsExactly("a", "b", "c", GROUPED);
    }
}
