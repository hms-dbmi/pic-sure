package edu.harvard.dbmi.avillach.visualization.processing;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.visualization.model.ContinuousDistributionData;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContinuousDistributionProcessorTest {

    private ContinuousDistributionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ContinuousDistributionProcessor();
    }

    @Test
    void process_preBinnedValues_returnsContinuousDistribution() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put(
            "\\measurements\\bmi\\",
            new LinkedHashMap<>(Map.of(
                "18.0 - 24.0", new ObfuscatedCount(600, "600"),
                "24.0 - 30.0", new ObfuscatedCount(700, "700"),
                "30.0 +", new ObfuscatedCount(150, "150")
            ))
        );

        List<ContinuousDistributionData> result = processor.process(data, false);

        assertEquals(1, result.size());
        ContinuousDistributionData distribution = result.get(0);
        assertEquals("\\measurements\\bmi\\", distribution.conceptPath());
        assertEquals("measurements: bmi", distribution.title());
        assertTrue(distribution.continuous());
        assertFalse(distribution.obfuscated());
        ObfuscatedCount firstBin = distribution.continuousMap().get("18.0 - 24.0");
        assertEquals(600, firstBin.count());
        assertEquals("600", firstBin.display());
    }

    @Test
    void process_obfuscatedValues_passedThroughUnchanged() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put(
            "\\measurements\\bmi\\",
            new LinkedHashMap<>(Map.of(
                "18.0 - 22.0", new ObfuscatedCount(500, "500 ±3", 3),
                "22.0 - 26.0", new ObfuscatedCount(0, "< 10", 9)
            ))
        );

        List<ContinuousDistributionData> result = processor.process(data, true);

        assertEquals(1, result.size());
        assertTrue(result.get(0).obfuscated());
        ObfuscatedCount lo = result.get(0).continuousMap().get("18.0 - 22.0");
        assertEquals(500, lo.count());
        assertEquals("500 ±3", lo.display());
        assertEquals(Integer.valueOf(3), lo.variance());
        ObfuscatedCount hi = result.get(0).continuousMap().get("22.0 - 26.0");
        assertEquals(0, hi.count());
        assertEquals("< 10", hi.display());
        assertEquals(Integer.valueOf(9), hi.variance());
    }

    @Test
    void process_skipsConsentKeysAndEmptySeries() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put("\\_consents\\", Map.of("1.0", new ObfuscatedCount(100, "100")));
        data.put("\\empty\\", Map.of());
        data.put("\\measurements\\bmi\\", new LinkedHashMap<>(Map.of("25.0", new ObfuscatedCount(100, "100"))));

        List<ContinuousDistributionData> result = processor.process(data, false);

        assertEquals(1, result.size());
    }

    @Test
    void process_nullInnerMap_skippedWithoutCrash() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put("\\measurements\\bmi\\", null);
        data.put("\\measurements\\age\\", new LinkedHashMap<>(Map.of("25.0", new ObfuscatedCount(100, "100"))));

        List<ContinuousDistributionData> result = processor.process(data, false);

        assertEquals(1, result.size());
        assertEquals("\\measurements\\age\\", result.get(0).conceptPath());
    }
}
