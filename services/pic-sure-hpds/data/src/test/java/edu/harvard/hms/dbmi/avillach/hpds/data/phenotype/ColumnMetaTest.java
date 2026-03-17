package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype;

import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMetaTest {

    @Test
    void shouldCreateColumnMetaWithDefaultTimestampValues() {
        ColumnMeta columnMeta = new ColumnMeta();

        assertFalse(columnMeta.hasTimestamp());
        assertNull(columnMeta.getTimestampMin());
        assertNull(columnMeta.getTimestampMax());
    }

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

    @Test
    void shouldSerializeWithTimestampFields() throws IOException, ClassNotFoundException {
        ColumnMeta original = new ColumnMeta()
                .setName("timestamped_column")
                .setHasTimestamp(true)
                .setTimestampMin(1609459200000L)
                .setTimestampMax(1640995200000L)
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
        assertFalse(deserialized.hasTimestamp());
        assertNull(deserialized.getTimestampMin());
        assertNull(deserialized.getTimestampMax());
    }

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
