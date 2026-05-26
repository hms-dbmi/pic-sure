package edu.harvard.dbmi.avillach.visualization.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ObfuscationParserTest {

    private ObfuscationParser parser;

    @BeforeEach
    void setUp() {
        parser = new ObfuscationParser(10, 3);
    }

    @Test
    void isObfuscated_withThresholdMarker_returnsTrue() {
        Map<String, Map<String, String>> data = Map.of("\\demographics\\race\\", Map.of("White", "45000", "Black", "< 10"));
        assertTrue(parser.isObfuscated(data));
    }

    @Test
    void isObfuscated_withVarianceMarker_returnsTrue() {
        Map<String, Map<String, String>> data = Map.of("\\demographics\\race\\", Map.of("White", "45000±3", "Black", "12000"));
        assertTrue(parser.isObfuscated(data));
    }

    @Test
    void isObfuscated_withNoMarkers_returnsFalse() {
        Map<String, Map<String, String>> data = Map.of("\\demographics\\race\\", Map.of("White", "45000", "Black", "12000"));
        assertFalse(parser.isObfuscated(data));
    }

    @Test
    void clean_replacesThresholdWithThresholdMinusOne() {
        Map<String, Map<String, String>> data = new HashMap<>();
        data.put("\\demographics\\race\\", new HashMap<>(Map.of("White", "45000", "Other", "< 10")));

        Map<String, Map<String, Integer>> result = parser.clean(data);

        assertEquals(45000, result.get("\\demographics\\race\\").get("White"));
        assertEquals(9, result.get("\\demographics\\race\\").get("Other"));
    }

    @Test
    void clean_stripsVarianceMarker() {
        Map<String, Map<String, String>> data = new HashMap<>();
        data.put("\\demographics\\race\\", new HashMap<>(Map.of("White", "45000±3")));

        Map<String, Map<String, Integer>> result = parser.clean(data);

        assertEquals(45000, result.get("\\demographics\\race\\").get("White"));
    }

    @Test
    void clean_skipsHarmonizedConsentKey() {
        Map<String, Map<String, String>> data = new HashMap<>();
        data.put("\\_harmonized_consent\\", Map.of("consent1", "100"));
        data.put("\\demographics\\race\\", new HashMap<>(Map.of("White", "45000")));

        Map<String, Map<String, Integer>> result = parser.clean(data);

        assertFalse(result.containsKey("\\_harmonized_consent\\"));
        assertTrue(result.containsKey("\\demographics\\race\\"));
    }
}
