package edu.harvard.dbmi.avillach.visualization.processing;

import static org.junit.jupiter.api.Assertions.*;

import edu.harvard.dbmi.avillach.visualization.model.CategoricalDistributionData;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoricalDistributionProcessorTest {

    private CategoricalDistributionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CategoricalDistributionProcessor();
    }

    @Test
    void process_simpleCategoricalData_returnsDistributionData() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(Map.of(
                "White", new ObfuscatedCount(45000, "45000"),
                "Black", new ObfuscatedCount(12000, "12000"),
                "Asian", new ObfuscatedCount(8000, "8000")
            ))
        );

        List<CategoricalDistributionData> result = processor.process(data, false);

        assertEquals(1, result.size());
        CategoricalDistributionData distribution = result.get(0);
        assertEquals("\\demographics\\race\\", distribution.conceptPath());
        assertEquals("demographics: race", distribution.title());
        assertFalse(distribution.continuous());
        assertFalse(distribution.obfuscated());
        ObfuscatedCount white = distribution.categoricalMap().get("White");
        assertEquals(45000, white.count());
        assertEquals("45000", white.display());
        assertEquals("race", distribution.xaxisName());
        assertEquals("Number of Participants", distribution.yaxisName());
    }

    @Test
    void process_obfuscatedValues_passedThroughUnchanged() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(Map.of(
                "White", new ObfuscatedCount(45000, "45000 ±3", 3),
                "Black", new ObfuscatedCount(0, "< 10", 9)
            ))
        );

        List<CategoricalDistributionData> result = processor.process(data, true);

        assertEquals(1, result.size());
        assertTrue(result.get(0).obfuscated());
        ObfuscatedCount white = result.get(0).categoricalMap().get("White");
        assertEquals(45000, white.count());
        assertEquals("45000 ±3", white.display());
        assertEquals(Integer.valueOf(3), white.variance());
        ObfuscatedCount black = result.get(0).categoricalMap().get("Black");
        assertEquals(0, black.count());
        assertEquals("< 10", black.display());
        assertEquals(Integer.valueOf(9), black.variance());
    }

    @Test
    void process_skipsConsentKeysAndEmptySeries() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put("\\_consents\\", Map.of("consent1", new ObfuscatedCount(100, "100")));
        data.put("\\_harmonized_consent\\", Map.of("consent2", new ObfuscatedCount(200, "200")));
        data.put("\\empty\\", Map.of());
        data.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", new ObfuscatedCount(45000, "45000"))));

        List<CategoricalDistributionData> result = processor.process(data, false);

        assertEquals(1, result.size());
    }

    @Test
    void process_nullInnerMap_skippedWithoutCrash() {
        Map<String, Map<String, ObfuscatedCount>> data = new LinkedHashMap<>();
        data.put("\\demographics\\race\\", null);
        data.put("\\demographics\\sex\\", new LinkedHashMap<>(Map.of("Female", new ObfuscatedCount(100, "100"))));

        List<CategoricalDistributionData> result = processor.process(data, false);

        assertEquals(1, result.size());
        assertEquals("\\demographics\\sex\\", result.get(0).conceptPath());
    }

    @Test
    void metadata_extractsTitleAndXAxisLabel() {
        assertEquals("demographics: race", DistributionMetadata.titleFor("\\demographics\\race\\"));
        assertEquals("race", DistributionMetadata.xAxisLabelFor("\\demographics\\race\\"));
        assertEquals("race", DistributionMetadata.titleFor("race"));
    }

    @Test
    void metadata_depthOnePath_hasNoLeadingColon() {
        assertEquals("age", DistributionMetadata.titleFor("\\age\\"));
        assertEquals("age", DistributionMetadata.xAxisLabelFor("\\age\\"));
    }

    @Test
    void metadata_multiWordVariableName_keepsFullNameForXAxis() {
        assertEquals(
            "family history of heart attack",
            DistributionMetadata.xAxisLabelFor("\\demographics\\family history of heart attack\\")
        );
    }
}
