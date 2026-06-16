package edu.harvard.dbmi.avillach.visualization.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON wire shape that the visualization resource exchanges with
 * aggregate-data-sharing. If either side renames a field, this test fails
 * BEFORE the cross-repo contract silently breaks in production.
 */
class ObfuscatedCountShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesAsCountDisplayAndVarianceFields() throws JsonProcessingException {
        ObfuscatedCount value = new ObfuscatedCount(222, "222 ±3", 3);

        String json = mapper.writeValueAsString(value);

        assertEquals("{\"count\":222,\"display\":\"222 ±3\",\"variance\":3}", json);
    }

    @Test
    void serializesExactValuesWithNullVariance() throws JsonProcessingException {
        ObfuscatedCount value = ObfuscatedCount.ofInt(45000);

        String json = mapper.writeValueAsString(value);

        assertEquals("{\"count\":45000,\"display\":\"45000\",\"variance\":null}", json);
    }

    @Test
    void deserializesFromAggSideShape() throws JsonProcessingException {
        String aggSideJson = "{\"count\":0,\"display\":\"< 10\",\"variance\":9}";

        ObfuscatedCount value = mapper.readValue(aggSideJson, ObfuscatedCount.class);

        assertEquals(0, value.count());
        assertEquals("< 10", value.display());
        assertEquals(Integer.valueOf(9), value.variance());
    }

    @Test
    void deserializesWhenVarianceIsAbsent() throws JsonProcessingException {
        String aggSideJson = "{\"count\":12000,\"display\":\"12000\"}";

        ObfuscatedCount value = mapper.readValue(aggSideJson, ObfuscatedCount.class);

        assertEquals(12000, value.count());
        assertEquals("12000", value.display());
        assertNull(value.variance());
    }

    @Test
    void ofInt_factory_producesStringifiedDisplayAndNullVariance() {
        ObfuscatedCount value = ObfuscatedCount.ofInt(45000);

        assertEquals(45000, value.count());
        assertEquals("45000", value.display());
        assertNull(value.variance());
    }
}
