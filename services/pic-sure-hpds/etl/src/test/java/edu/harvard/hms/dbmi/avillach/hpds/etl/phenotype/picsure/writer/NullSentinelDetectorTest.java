package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NullSentinelDetector.
 *
 * Tests the detection of null sentinel values (string representations of missing data) to ensure numeric concepts are not incorrectly
 * promoted to categorical.
 */
class NullSentinelDetectorTest {

    @Test
    void testDefaultNullSentinels() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // Should detect all default sentinels (case-insensitive)
        assertTrue(detector.isNullSentinel(""), "Empty string should be null sentinel");
        assertTrue(detector.isNullSentinel("nan"), "lowercase nan should be null sentinel");
        assertTrue(detector.isNullSentinel("NaN"), "uppercase NaN should be null sentinel");
        assertTrue(detector.isNullSentinel("NA"), "uppercase NA should be null sentinel");
        assertTrue(detector.isNullSentinel("na"), "lowercase na should be null sentinel");
        assertTrue(detector.isNullSentinel("n/a"), "lowercase n/a should be null sentinel");
        assertTrue(detector.isNullSentinel("N/A"), "uppercase N/A should be null sentinel");
        assertTrue(detector.isNullSentinel("null"), "lowercase null should be null sentinel");
        assertTrue(detector.isNullSentinel("NULL"), "uppercase NULL should be null sentinel");
        assertTrue(detector.isNullSentinel("none"), "lowercase none should be null sentinel");
        assertTrue(detector.isNullSentinel("None"), "capitalized None should be null sentinel");
        assertTrue(detector.isNullSentinel("NONE"), "uppercase NONE should be null sentinel");
        assertTrue(detector.isNullSentinel("\\N"), "MySQL null marker should be null sentinel");
    }

    @Test
    void testWhitespaceHandling() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // Should trim whitespace before checking
        assertTrue(detector.isNullSentinel("  nan  "), "nan with surrounding spaces should be detected");
        assertTrue(detector.isNullSentinel("\tNone\t"), "None with tabs should be detected");
        assertTrue(detector.isNullSentinel("  NULL  "), "NULL with spaces should be detected");
        assertTrue(detector.isNullSentinel(" "), "Space should be detected as empty");
    }

    @Test
    void testNonSentinels() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // Should NOT detect non-sentinels
        assertFalse(detector.isNullSentinel("123"), "Numeric string should not be null sentinel");
        assertFalse(detector.isNullSentinel("123.45"), "Decimal string should not be null sentinel");
        assertFalse(detector.isNullSentinel("valid_string"), "Valid string should not be null sentinel");
        assertFalse(detector.isNullSentinel("Not Applicable"), "Full phrase should not be null sentinel");
        assertFalse(detector.isNullSentinel("banana"), "Random word should not be null sentinel");
    }

    @Test
    void testNullValue() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // Null object should be detected as null sentinel
        assertTrue(detector.isNullSentinel(null), "Null object should be null sentinel");
    }

    @Test
    void testNumericTypes() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // Numeric types should NOT be null sentinels
        assertFalse(detector.isNullSentinel(123), "Integer should not be null sentinel");
        assertFalse(detector.isNullSentinel(123.45), "Double should not be null sentinel");
        assertFalse(detector.isNullSentinel(0), "Zero should not be null sentinel");
        assertFalse(detector.isNullSentinel(0.0), "Zero double should not be null sentinel");
        assertFalse(detector.isNullSentinel(Double.NaN), "Double.NaN should not be null sentinel");
    }

    @Test
    void testCaseInsensitivity() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // All case variants should be detected
        assertTrue(detector.isNullSentinel("none"), "lowercase");
        assertTrue(detector.isNullSentinel("None"), "Capitalized");
        assertTrue(detector.isNullSentinel("NONE"), "UPPERCASE");
        assertTrue(detector.isNullSentinel("nOnE"), "MiXeD CaSe");

        assertTrue(detector.isNullSentinel("null"), "lowercase");
        assertTrue(detector.isNullSentinel("Null"), "Capitalized");
        assertTrue(detector.isNullSentinel("NULL"), "UPPERCASE");

        assertTrue(detector.isNullSentinel("nan"), "lowercase");
        assertTrue(detector.isNullSentinel("Nan"), "Capitalized");
        assertTrue(detector.isNullSentinel("NaN"), "MixedCase");
        assertTrue(detector.isNullSentinel("NAN"), "UPPERCASE");
    }

    @Test
    void testCustomSentinels() {
        // Create detector with custom sentinels
        Set<String> customSentinels = Set.of("missing", "unknown", "n/a");
        NullSentinelDetector detector = new NullSentinelDetector(customSentinels);

        // Should detect custom sentinels
        assertTrue(detector.isNullSentinel("missing"), "Custom sentinel 'missing' should be detected");
        assertTrue(detector.isNullSentinel("MISSING"), "Case-insensitive custom sentinel");
        assertTrue(detector.isNullSentinel("unknown"), "Custom sentinel 'unknown' should be detected");
        assertTrue(detector.isNullSentinel("n/a"), "Custom sentinel 'n/a' should be detected");

        // Should NOT detect default sentinels (only custom ones)
        assertFalse(detector.isNullSentinel("nan"), "Default sentinel should not be in custom set");
        assertFalse(detector.isNullSentinel("null"), "Default sentinel should not be in custom set");
    }

    @Test
    void testEmptyString() {
        NullSentinelDetector detector = new NullSentinelDetector();

        assertTrue(detector.isNullSentinel(""), "Empty string should be null sentinel");
        assertTrue(detector.isNullSentinel("   "), "Whitespace-only string should be detected as empty");
    }

    @Test
    void testGetNullSentinels() {
        NullSentinelDetector detector = new NullSentinelDetector();

        Set<String> sentinels = detector.getNullSentinels();

        // Verify sentinel set is not empty
        assertNotNull(sentinels, "Sentinel set should not be null");
        assertFalse(sentinels.isEmpty(), "Sentinel set should not be empty");

        // Verify it contains expected default sentinels
        assertTrue(sentinels.contains(""), "Should contain empty string");
        assertTrue(sentinels.contains("nan"), "Should contain 'nan'");
        assertTrue(sentinels.contains("null"), "Should contain 'null'");
        assertTrue(sentinels.contains("none"), "Should contain 'none'");

        // Verify returned set is immutable (defensive copy)
        assertThrows(UnsupportedOperationException.class, () -> {
            sentinels.add("new_sentinel");
        }, "Returned set should be immutable");
    }

    @Test
    void testAlignmentWithPythonETL() {
        NullSentinelDetector detector = new NullSentinelDetector();

        // These tokens MUST match parquet-metadata-rewriter/config.yaml null_tokens
        // This test ensures alignment between Python ETL and Java HPDS ingestion
        String[] pythonNullTokens = {"", // empty string
            "nan", // pandas NaN string
            "na", // R-style NA
            "n/a", // human-readable not applicable
            "null", // JSON null string
            "none", // Python None string
            "\\n", // MySQL null marker (normalized from "\\N")
            // Case variants handled by toLowerCase
        };

        for (String token : pythonNullTokens) {
            assertTrue(detector.isNullSentinel(token), String.format("Token '%s' from Python config should be detected", token));
        }
    }
}
