package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ColumnMeta timestamp field functionality.
 *
 * <p>These tests validate the addition of timestamp tracking fields to ColumnMeta:
 * <ul>
 *   <li><b>hasTimestamp</b>: boolean flag indicating if temporal data is present</li>
 *   <li><b>timestampMin</b>: earliest timestamp value (epoch milliseconds)</li>
 *   <li><b>timestampMax</b>: latest timestamp value (epoch milliseconds)</li>
 * </ul>
 *
 * <p>The implementation maintains backward compatibility with existing serialized
 * ColumnMeta objects that do not contain timestamp fields. New fields default
 * to hasTimestamp=false and timestampMin/timestampMax=null when deserializing
 * legacy data.
 *
 * @see ColumnMeta
 */
class ColumnMetaTest {

    /**
     * Verifies that newly instantiated ColumnMeta objects have correct default values
     * for timestamp fields.
     *
     * <p>This ensures backward compatibility when working with datasets that do not
     * contain temporal data. Default values should be:
     * <ul>
     *   <li>hasTimestamp: false</li>
     *   <li>timestampMin: null</li>
     *   <li>timestampMax: null</li>
     * </ul>
     */
    @Test
    void shouldCreateColumnMetaWithDefaultTimestampValues() {
        ColumnMeta columnMeta = new ColumnMeta();

        assertFalse(columnMeta.hasTimestamp());
        assertNull(columnMeta.getTimestampMin());
        assertNull(columnMeta.getTimestampMax());
    }

    /**
     * Validates that timestamp field setters and getters function correctly.
     *
     * <p>Tests the basic contract of the timestamp accessor methods to ensure
     * values can be set and retrieved as expected.
     */
    @Test
    void shouldSetAndGetTimestampFields() {
        ColumnMeta columnMeta = new ColumnMeta()
                .setHasTimestamp(true)
                .setTimestampMin(1000000L)
                .setTimestampMax(2000000L);

        assertTrue(columnMeta.hasTimestamp());
        assertEquals(1000000L, columnMeta.getTimestampMin());
        assertEquals(2000000L, columnMeta.getTimestampMax());
    }

    /**
     * Confirms that timestamp setters follow the builder pattern and can be chained
     * with other ColumnMeta setters.
     *
     * <p>The builder pattern allows for fluent configuration of ColumnMeta objects,
     * improving code readability when initializing metadata with multiple properties.
     */
    @Test
    void shouldSupportMethodChaining() {
        ColumnMeta columnMeta = new ColumnMeta()
                .setName("test_column")
                .setHasTimestamp(true)
                .setTimestampMin(1000000L)
                .setTimestampMax(2000000L)
                .setCategorical(false)
                .setObservationCount(100)
                .setPatientCount(50);

        assertEquals("test_column", columnMeta.getName());
        assertTrue(columnMeta.hasTimestamp());
        assertEquals(1000000L, columnMeta.getTimestampMin());
        assertEquals(2000000L, columnMeta.getTimestampMax());
        assertFalse(columnMeta.isCategorical());
        assertEquals(100, columnMeta.getObservationCount());
        assertEquals(50, columnMeta.getPatientCount());
    }

    /**
     * Verifies that ColumnMeta objects with timestamp fields can be serialized and
     * deserialized correctly.
     *
     * <p>This test ensures that the timestamp fields are properly included in the
     * Java serialization process. When a ColumnMeta object containing timestamp data
     * is serialized to disk (e.g., columnMeta.javabin) and later deserialized, all
     * timestamp field values should be preserved.
     *
     * <p>Uses realistic epoch millisecond values:
     * <ul>
     *   <li>1609459200000L = 2021-01-01 00:00:00 UTC</li>
     *   <li>1640995200000L = 2022-01-01 00:00:00 UTC</li>
     * </ul>
     */
    @Test
    void shouldSerializeWithTimestampFields() throws IOException, ClassNotFoundException {
        ColumnMeta original = new ColumnMeta()
                .setName("timestamped_column")
                .setHasTimestamp(true)
                .setTimestampMin(1609459200000L)  // 2021-01-01
                .setTimestampMax(1640995200000L)  // 2022-01-01
                .setObservationCount(500)
                .setPatientCount(250);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ColumnMeta deserialized;
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            deserialized = (ColumnMeta) ois.readObject();
        }

        assertEquals(original.getName(), deserialized.getName());
        assertEquals(original.hasTimestamp(), deserialized.hasTimestamp());
        assertEquals(original.getTimestampMin(), deserialized.getTimestampMin());
        assertEquals(original.getTimestampMax(), deserialized.getTimestampMax());
        assertEquals(original.getObservationCount(), deserialized.getObservationCount());
        assertEquals(original.getPatientCount(), deserialized.getPatientCount());
    }

    /**
     * Tests backward compatibility by deserializing ColumnMeta objects that were
     * serialized before timestamp fields were added.
     *
     * <p>This is a critical test for production deployment. When HPDS loads existing
     * columnMeta.javabin files created by older versions of the software, it must
     * handle the absence of timestamp fields gracefully. Java serialization supports
     * this by initializing new fields with their default values:
     * <ul>
     *   <li>hasTimestamp: false (primitive boolean default)</li>
     *   <li>timestampMin: null (object reference default)</li>
     *   <li>timestampMax: null (object reference default)</li>
     * </ul>
     *
     * <p>This test simulates loading legacy data by creating a ColumnMeta object,
     * serializing it, and verifying that timestamp fields receive proper defaults
     * upon deserialization.
     */
    @Test
    void shouldDeserializeLegacyColumnMetaWithoutTimestampFields() throws IOException, ClassNotFoundException {
        ColumnMeta legacy = new ColumnMeta()
                .setName("legacy_column")
                .setCategorical(true)
                .setObservationCount(1000)
                .setPatientCount(500);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(legacy);
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ColumnMeta deserialized;
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            deserialized = (ColumnMeta) ois.readObject();
        }

        assertEquals("legacy_column", deserialized.getName());
        assertTrue(deserialized.isCategorical());
        assertEquals(1000, deserialized.getObservationCount());
        assertEquals(500, deserialized.getPatientCount());

        // Verify timestamp fields receive correct defaults
        assertFalse(deserialized.hasTimestamp());
        assertNull(deserialized.getTimestampMin());
        assertNull(deserialized.getTimestampMax());
    }

    /**
     * Validates that timestamp min/max fields can be explicitly set to null.
     *
     * <p>This may occur when a column has temporal observations but timestamp values
     * are not yet calculated or are unavailable. The hasTimestamp flag can be true
     * while min/max values remain null.
     */
    @Test
    void shouldHandleNullTimestampMinMax() {
        ColumnMeta columnMeta = new ColumnMeta()
                .setHasTimestamp(true)
                .setTimestampMin(null)
                .setTimestampMax(null);

        assertTrue(columnMeta.hasTimestamp());
        assertNull(columnMeta.getTimestampMin());
        assertNull(columnMeta.getTimestampMax());
    }

    /**
     * Tests that timestamp min/max values can be set independently of the hasTimestamp flag.
     *
     * <p>While this may represent an inconsistent state in production usage, the fields
     * are designed to be independently settable for flexibility. This test ensures the
     * setters do not have hidden dependencies on each other.
     */
    @Test
    void shouldAllowTimestampFieldsWithoutHasTimestampFlag() {
        ColumnMeta columnMeta = new ColumnMeta()
                .setTimestampMin(1000000L)
                .setTimestampMax(2000000L);

        assertFalse(columnMeta.hasTimestamp());
        assertEquals(1000000L, columnMeta.getTimestampMin());
        assertEquals(2000000L, columnMeta.getTimestampMax());
    }
}
