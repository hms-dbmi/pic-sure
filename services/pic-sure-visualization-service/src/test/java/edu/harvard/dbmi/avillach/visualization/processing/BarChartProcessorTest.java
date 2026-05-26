package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ChartData;
import edu.harvard.dbmi.avillach.visualization.model.ChartType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BarChartProcessorTest {

    private BarChartProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BarChartProcessor(7);
    }

    @Test
    void chartType_returnsBar() {
        assertEquals(ChartType.BAR, processor.chartType());
    }

    @Test
    void process_simpleCategoricalData_returnsBarChart() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000, "Black", 12000, "Asian", 8000)));

        List<ChartData> result = processor.process(data, false);

        assertEquals(1, result.size());
        ChartData chart = result.get(0);
        assertEquals("bar", chart.chartType());
        assertEquals("Variable distribution of demographics: race", chart.title());
        assertFalse(chart.isObfuscated());
        assertFalse(chart.traces().isEmpty());
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
        assertEquals(50, result.get("Other")); // 30 + 20
    }

    @Test
    void aggregateTopN_exactlyOneOverMax_createsOtherBucket() {
        // maxCategories=7, so 8 categories should trigger aggregation (7 + Other)
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
        assertEquals(30, result.get("Other")); // Cat8 rolled into Other
        assertEquals(8, result.size()); // 7 real + Other
    }

    @Test
    void aggregateTopN_exactlyAtMax_noOtherBucket() {
        // maxCategories=7, so exactly 7 categories should NOT aggregate
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
    void aggregateTopN_fewCategories_noOtherBucket() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("Cat1", 100);
        categories.put("Cat2", 90);

        Map<String, Integer> result = processor.aggregateTopN(categories);

        assertFalse(result.containsKey("Other"));
        assertEquals(2, result.size());
    }

    @Test
    void process_skipsConsentKeys() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\_consents\\", Map.of("consent1", 100));
        data.put("\\_harmonized_consent\\", Map.of("consent2", 200));
        data.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000)));

        List<ChartData> result = processor.process(data, false);

        assertEquals(1, result.size());
    }

    @Test
    void process_obfuscatedFlag_passedThrough() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000)));

        List<ChartData> result = processor.process(data, true);

        assertTrue(result.get(0).isObfuscated());
    }

    @Test
    void getChartTitle_extractsLastTwoComponents() {
        assertEquals("Variable distribution of demographics: race", processor.getChartTitle("\\demographics\\race\\"));
    }

    @Test
    void getChartTitle_singleComponent_returnsAsIs() {
        assertEquals("race", processor.getChartTitle("race"));
    }

    @Test
    void createXAxisLabel_extractsLastWord() {
        assertEquals("race", processor.createXAxisLabel("Variable distribution of demographics: race"));
    }
}
