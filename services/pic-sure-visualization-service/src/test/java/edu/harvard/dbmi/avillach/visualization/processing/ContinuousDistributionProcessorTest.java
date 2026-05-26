package edu.harvard.dbmi.avillach.visualization.processing;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.visualization.model.ContinuousDistributionData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContinuousDistributionProcessorTest {

    private ContinuousDistributionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ContinuousDistributionProcessor(new BinningService());
    }

    @Test
    void process_preBinnedData_returnsContinuousDistribution() {
        Map<String, Integer> binnedValues = new LinkedHashMap<>();
        binnedValues.put("18.0 - 24.0", 600);
        binnedValues.put("24.0 - 30.0", 700);
        binnedValues.put("30.0 +", 150);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", binnedValues);

        List<ContinuousDistributionData> result = processor.process(
            data,
            false,
            false
        );

        assertEquals(1, result.size());
        ContinuousDistributionData distribution = result.get(0);
        assertEquals("\\measurements\\bmi\\", distribution.conceptPath());
        assertEquals("measurements: bmi", distribution.title());
        assertTrue(distribution.continuous());
        assertFalse(distribution.obfuscated());
        assertEquals(
            List.of("18.0 - 24.0", "24.0 - 30.0", "30.0 +"),
            List.copyOf(distribution.continuousMap().keySet())
        );
        assertEquals(
            1450,
            distribution
                .continuousMap()
                .values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum()
        );
    }

    @Test
    void process_binsRawDataWhenRequested() {
        Map<String, Integer> rawValues = new LinkedHashMap<>();
        rawValues.put("18.0", 100);
        rawValues.put("22.0", 200);
        rawValues.put("26.0", 150);
        rawValues.put("30.0", 100);
        rawValues.put("35.0", 50);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", rawValues);

        List<ContinuousDistributionData> result = processor.process(
            data,
            false,
            true
        );

        Map<String, Integer> binnedBmi = result.get(0).continuousMap();
        assertFalse(binnedBmi.containsKey("18.0"));
        assertEquals(
            600,
            binnedBmi.values().stream().mapToInt(Integer::intValue).sum()
        );
    }

    @Test
    void process_obfuscatedData_setsFlag() {
        Map<String, Integer> preBinned = new LinkedHashMap<>();
        preBinned.put("18.0 - 22.0", 500);
        preBinned.put("22.0 - 26.0", 450);
        preBinned.put("26.0 +", 250);

        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", preBinned);

        List<ContinuousDistributionData> result = processor.process(
            data,
            true,
            false
        );

        assertEquals(1, result.size());
        assertTrue(result.get(0).obfuscated());
        assertEquals(
            List.of("18.0 - 22.0", "22.0 - 26.0", "26.0 +"),
            List.copyOf(result.get(0).continuousMap().keySet())
        );
    }

    @Test
    void process_skipsConsentKeysAndEmptySeries() {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        data.put("\\_consents\\", Map.of("1.0", 100));
        data.put("\\empty\\", Map.of());
        data.put(
            "\\measurements\\bmi\\",
            new LinkedHashMap<>(Map.of("25.0", 100))
        );

        List<ContinuousDistributionData> result = processor.process(
            data,
            false,
            false
        );

        assertEquals(1, result.size());
    }
}
