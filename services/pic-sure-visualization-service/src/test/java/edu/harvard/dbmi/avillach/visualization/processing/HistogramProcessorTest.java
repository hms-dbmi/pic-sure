package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ChartData;
import edu.harvard.dbmi.avillach.visualization.model.ChartType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HistogramProcessorTest {

    private HistogramProcessor processor;

    @BeforeEach
    void setUp() {
        BinningService binningService = new BinningService();
        processor = new HistogramProcessor(binningService);
    }

    @Test
    void chartType_returnsHistogram() {
        assertEquals(ChartType.HISTOGRAM, processor.chartType());
    }

    @Test
    void process_preBinnedData_returnsHistogramChart() {
        Map<String, Integer> binnedValues = new LinkedHashMap<>();
        binnedValues.put("18.0 - 24.0", 600);
        binnedValues.put("24.0 - 30.0", 700);
        binnedValues.put("30.0 +", 150);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", binnedValues);

        List<ChartData> result = processor.process(data, false);

        assertEquals(1, result.size());
        ChartData chart = result.get(0);
        assertEquals("histogram", chart.chartType());
        assertFalse(chart.isObfuscated());
        assertFalse(chart.traces().isEmpty());

        @SuppressWarnings("unchecked")
        List<Integer> yValues = (List<Integer>) chart.traces().get(0).get("y");
        int totalOutput = yValues.stream().mapToInt(Integer::intValue).sum();
        assertEquals(1450, totalOutput);
    }

    @Test
    void preProcess_binsRawData() {
        Map<String, Integer> rawValues = new LinkedHashMap<>();
        rawValues.put("18.0", 100);
        rawValues.put("22.0", 200);
        rawValues.put("26.0", 150);
        rawValues.put("30.0", 100);
        rawValues.put("35.0", 50);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", rawValues);

        Map<String, Map<String, Integer>> result = processor.preProcess(data);

        assertTrue(result.containsKey("\\measurements\\bmi\\"));
        // Binned data should have range labels, not raw values
        Map<String, Integer> binnedBmi = result.get("\\measurements\\bmi\\");
        assertFalse(binnedBmi.containsKey("18.0"));
        // Total counts should be preserved
        int totalOutput = binnedBmi.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(600, totalOutput);
    }

    @Test
    void process_obfuscatedData_setsFlag() {
        Map<String, Integer> preBinned = new LinkedHashMap<>();
        preBinned.put("18.0 - 22.0", 500);
        preBinned.put("22.0 - 26.0", 450);
        preBinned.put("26.0 +", 250);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", preBinned);

        List<ChartData> result = processor.process(data, true);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isObfuscated());

        @SuppressWarnings("unchecked")
        List<String> xValues = (List<String>) result.get(0).traces().get(0).get("x");
        assertEquals(List.of("18.0 - 22.0", "22.0 - 26.0", "26.0 +"), xValues);
    }

    @Test
    void process_skipsConsentKeys() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\_consents\\", Map.of("1.0", 100));
        data.put("\\measurements\\bmi\\", new LinkedHashMap<>(Map.of("25.0", 100)));

        List<ChartData> result = processor.process(data, false);

        assertEquals(1, result.size());
    }
}
