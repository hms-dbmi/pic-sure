package edu.harvard.dbmi.avillach.visualization.processing;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.visualization.model.CategoricalDistributionData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoricalDistributionProcessorTest {

    private CategoricalDistributionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CategoricalDistributionProcessor(7);
    }

    @Test
    void process_simpleCategoricalData_returnsDistributionData() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(
                Map.of("White", 45000, "Black", 12000, "Asian", 8000)
            )
        );

        List<CategoricalDistributionData> result = processor.process(
            data,
            false,
            true
        );

        assertEquals(1, result.size());
        CategoricalDistributionData distribution = result.get(0);
        assertEquals("\\demographics\\race\\", distribution.conceptPath());
        assertEquals("demographics: race", distribution.title());
        assertFalse(distribution.continuous());
        assertFalse(distribution.obfuscated());
        assertFalse(distribution.categoricalMap().isEmpty());
        assertEquals("race", distribution.xaxisName());
        assertEquals("Number of Participants", distribution.yaxisName());
    }

    @Test
    void aggregateTopN_moreThanMaxCategories_createsOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);
        categories.put("Cat8", 30);
        categories.put("Cat9", 20);

        Map<String, Integer> result = processor.aggregateTopN(categories);

        assertTrue(result.containsKey("Other"));
        assertEquals(50, result.get("Other"));
    }

    @Test
    void aggregateTopN_exactlyOneOverMax_createsOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);
        categories.put("Cat8", 30);

        Map<String, Integer> result = processor.aggregateTopN(categories);

        assertTrue(result.containsKey("Other"));
        assertEquals(30, result.get("Other"));
        assertEquals(8, result.size());
    }

    @Test
    void aggregateTopN_exactlyAtMax_noOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);

        Map<String, Integer> result = processor.aggregateTopN(categories);

        assertFalse(result.containsKey("Other"));
        assertEquals(7, result.size());
    }

    @Test
    void process_canSkipAggregationForOpenData() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);
        categories.put("Cat3", 80);
        categories.put("Cat4", 70);
        categories.put("Cat5", 60);
        categories.put("Cat6", 50);
        categories.put("Cat7", 40);
        categories.put("Cat8", 30);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\demographics\\race\\", categories);

        List<CategoricalDistributionData> result = processor.process(
            data,
            true,
            false
        );

        assertFalse(result.get(0).categoricalMap().containsKey("Other"));
        assertTrue(result.get(0).obfuscated());
        assertEquals(8, result.get(0).categoricalMap().size());
    }

    @Test
    void process_skipsConsentKeysAndEmptySeries() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\_consents\\", Map.of("consent1", 100));
        data.put("\\_harmonized_consent\\", Map.of("consent2", 200));
        data.put("\\empty\\", Map.of());
        data.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(Map.of("White", 45000))
        );

        List<CategoricalDistributionData> result = processor.process(
            data,
            false,
            true
        );

        assertEquals(1, result.size());
    }

    @Test
    void metadata_extractsTitleAndXAxisLabel() {
        String title = DistributionMetadata.titleFor("\\demographics\\race\\");

        assertEquals("demographics: race", title);
        assertEquals("race", DistributionMetadata.xAxisLabelFor(title));
        assertEquals("race", DistributionMetadata.titleFor("race"));
    }
}
