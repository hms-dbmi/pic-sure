package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMetaBuilderTest {

    @Test
    void testFromPhenoCube_Categorical() {
        // Create categorical PhenoCube with manual sortedByKey setup
        PhenoCube<String> cube = new PhenoCube<>("\\test\\categorical\\", String.class);
        KeyAndValue<String>[] sortedByKey = new KeyAndValue[] {new KeyAndValue<>(1, "Value1", null), new KeyAndValue<>(1, "Value2", null),
            new KeyAndValue<>(2, "Value1", null), new KeyAndValue<>(3, "Value3", null)};
        cube.setSortedByKey(sortedByKey);
        cube.setColumnWidth(6); // "Value1".length()

        // Build metadata without width override (should calculate from data)
        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        // Assertions
        assertEquals("\\test\\categorical\\", meta.getName());
        assertTrue(meta.isCategorical());
        assertEquals(4, meta.getObservationCount()); // 4 observations
        assertEquals(3, meta.getPatientCount()); // 3 unique patients
        assertEquals(6, meta.getWidthInBytes()); // Calculated from "Value1", "Value2", "Value3"

        // Category values should be sorted and unique
        List<String> categoryValues = meta.getCategoryValues();
        assertNotNull(categoryValues);
        assertEquals(3, categoryValues.size());
        assertEquals("Value1", categoryValues.get(0));
        assertEquals("Value2", categoryValues.get(1));
        assertEquals("Value3", categoryValues.get(2));

        // Min/max should be null for categorical
        assertNull(meta.getMin());
        assertNull(meta.getMax());
    }

    @Test
    void testFromPhenoCube_Categorical_WithWidthOverride() {
        PhenoCube<String> cube = new PhenoCube<>("\\test\\concept\\", String.class);
        cube.setSortedByKey(new KeyAndValue[] {new KeyAndValue<>(1, "A", null), new KeyAndValue<>(2, "B", null)});

        // Build with width override
        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube, 10);

        assertEquals(10, meta.getWidthInBytes()); // Override applied
    }

    @Test
    void testFromPhenoCube_Numeric() {
        // Create numeric PhenoCube
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\numeric\\", Double.class);
        cube.setSortedByKey(
            new KeyAndValue[] {new KeyAndValue<>(1, 10.5, null), new KeyAndValue<>(1, 20.3, null), new KeyAndValue<>(2, 5.2, null),
                new KeyAndValue<>(3, 15.7, null), new KeyAndValue<>(3, 30.9, null)}
        );

        // Build metadata
        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        // Assertions
        assertEquals("\\test\\numeric\\", meta.getName());
        assertFalse(meta.isCategorical());
        assertEquals(5, meta.getObservationCount());
        assertEquals(3, meta.getPatientCount());
        assertEquals(1, meta.getWidthInBytes()); // Always 1 for numeric

        // Min/max should be calculated
        assertNotNull(meta.getMin());
        assertNotNull(meta.getMax());
        assertEquals(5.2, meta.getMin(), 0.001);
        assertEquals(30.9, meta.getMax(), 0.001);

        // Category values should be null for numeric
        assertNull(meta.getCategoryValues());
    }

    @Test
    void testFromPhenoCube_Numeric_WithWidthOverride() {
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\concept\\", Double.class);
        cube.setSortedByKey(new KeyAndValue[] {new KeyAndValue<>(1, 1.0, null), new KeyAndValue<>(2, 2.0, null)});

        // Build with width override (unusual but should work)
        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube, 5);

        assertEquals(5, meta.getWidthInBytes()); // Override applied
    }

    @Test
    void testFromPhenoCube_EmptyValues() {
        // Test with empty cube
        PhenoCube<String> cube = new PhenoCube<>("\\test\\empty\\", String.class);
        cube.setSortedByKey(new KeyAndValue[0]); // Empty array

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        assertEquals("\\test\\empty\\", meta.getName());
        assertEquals(0, meta.getObservationCount());
        assertEquals(0, meta.getPatientCount());
        assertEquals(1, meta.getWidthInBytes()); // Default when no categories
    }

    @Test
    void testFromPhenoCube_NullValues() {
        // Test null-safety in categorical values
        PhenoCube<String> cube = new PhenoCube<>("\\test\\nulls\\", String.class);
        cube.setSortedByKey(
            new KeyAndValue[] {new KeyAndValue<>(1, "Value1", null), new KeyAndValue<>(2, null, null), // Should be filtered out
                new KeyAndValue<>(3, "Value2", null)}
        );

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        // Should only include non-null values
        List<String> categoryValues = meta.getCategoryValues();
        assertNotNull(categoryValues);
        assertEquals(2, categoryValues.size());
        assertFalse(categoryValues.contains(null));
    }

    @Test
    void testWriteColumnMetaCsv(@TempDir Path tempDir) throws IOException {
        // Create test metadata
        TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();

        ColumnMeta meta1 = new ColumnMeta().setName("\\concept1\\").setWidthInBytes(5).setColumnOffset(0).setCategorical(true)
            .setAllObservationsOffset(100).setAllObservationsLength(200).setObservationCount(10).setPatientCount(5);
        meta1.setCategoryValues(List.of("A", "B", "C"));
        metadataMap.put("\\concept1\\", meta1);

        ColumnMeta meta2 = new ColumnMeta().setName("\\concept2\\").setWidthInBytes(1).setColumnOffset(0).setCategorical(false).setMin(1.0)
            .setMax(10.0).setAllObservationsOffset(200).setAllObservationsLength(300).setObservationCount(20).setPatientCount(8);
        metadataMap.put("\\concept2\\", meta2);

        // Write CSV
        Path csvPath = tempDir.resolve("columnMeta.csv");
        ColumnMetaBuilder.writeColumnMetaCsv(metadataMap, csvPath);

        // Verify file exists and has content
        assertTrue(Files.exists(csvPath));
        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(2, lines.size()); // 2 concepts

        // Verify first line contains concept1 data
        String line1 = lines.get(0);
        assertTrue(line1.contains("\\concept1\\"));
        assertTrue(line1.contains("true")); // isCategorical
        assertTrue(line1.contains("AµBµC")); // Category values with µ delimiter

        // Verify second line contains concept2 data
        String line2 = lines.get(1);
        assertTrue(line2.contains("\\concept2\\"));
        assertTrue(line2.contains("false")); // isCategorical
        assertTrue(line2.contains("1.0")); // min
        assertTrue(line2.contains("10.0")); // max
    }

    @Test
    void testWriteColumnMetaCsv_EmptyMetadata(@TempDir Path tempDir) throws IOException {
        TreeMap<String, ColumnMeta> emptyMap = new TreeMap<>();
        Path csvPath = tempDir.resolve("empty.csv");

        // Should not throw, just create empty file
        assertDoesNotThrow(() -> ColumnMetaBuilder.writeColumnMetaCsv(emptyMap, csvPath));
        assertTrue(Files.exists(csvPath));
    }

    @Test
    void testFromPhenoCube_WithTimestamps() {
        // Create PhenoCube with all observations having timestamps
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\timestamped\\", Double.class);
        KeyAndValue<Double>[] sortedByKey = new KeyAndValue[] {new KeyAndValue<>(1, 10.5, 1609459200000L), // 2021-01-01
            new KeyAndValue<>(2, 20.3, 1640995200000L), // 2022-01-01
            new KeyAndValue<>(3, 15.7, 1672531200000L) // 2023-01-01
        };
        cube.setSortedByKey(sortedByKey);

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        // Verify timestamp fields are populated
        assertTrue(meta.hasTimestamp(), "hasTimestamp should be true when all observations have timestamps");
        assertNotNull(meta.getTimestampMin());
        assertNotNull(meta.getTimestampMax());
        assertEquals(1609459200000L, meta.getTimestampMin()); // Earliest timestamp
        assertEquals(1672531200000L, meta.getTimestampMax()); // Latest timestamp
    }

    @Test
    void testFromPhenoCube_MixedTimestamps() {
        // Create PhenoCube with some observations having timestamps, some without (coexistence)
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\mixed\\", Double.class);
        KeyAndValue<Double>[] sortedByKey = new KeyAndValue[] {new KeyAndValue<>(1, 10.5, 1609459200000L), // Has timestamp
            new KeyAndValue<>(2, 20.3, null), // No timestamp
            new KeyAndValue<>(3, 15.7, Long.MIN_VALUE), // Sentinel value (no timestamp)
            new KeyAndValue<>(4, 30.2, 1640995200000L) // Has timestamp
        };
        cube.setSortedByKey(sortedByKey);

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        // Should set hasTimestamp=true if ANY timestamp exists
        assertTrue(meta.hasTimestamp(), "hasTimestamp should be true when at least one observation has timestamp");
        assertNotNull(meta.getTimestampMin());
        assertNotNull(meta.getTimestampMax());
        assertEquals(1609459200000L, meta.getTimestampMin()); // Min of valid timestamps
        assertEquals(1640995200000L, meta.getTimestampMax()); // Max of valid timestamps
    }

    @Test
    void testFromPhenoCube_NoTimestamps() {
        // Create PhenoCube with no timestamps
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\no_timestamps\\", Double.class);
        KeyAndValue<Double>[] sortedByKey = new KeyAndValue[] {new KeyAndValue<>(1, 10.5, null), new KeyAndValue<>(2, 20.3, Long.MIN_VALUE), // Sentinel
                                                                                                                                             // value
            new KeyAndValue<>(3, 15.7, null)};
        cube.setSortedByKey(sortedByKey);

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        // Should NOT set hasTimestamp when no valid timestamps exist
        assertFalse(meta.hasTimestamp(), "hasTimestamp should be false when no valid timestamps exist");
        assertNull(meta.getTimestampMin());
        assertNull(meta.getTimestampMax());
    }

    @Test
    void testWriteColumnMetaCsv_WithTimestamps(@TempDir Path tempDir) throws IOException {
        // Create ColumnMeta with timestamp data
        TreeMap<String, ColumnMeta> metadataMap = new TreeMap<>();

        ColumnMeta meta1 = new ColumnMeta().setName("\\timestamped_concept\\").setWidthInBytes(1).setColumnOffset(0).setCategorical(false)
            .setMin(1.0).setMax(10.0).setAllObservationsOffset(100).setAllObservationsLength(200).setObservationCount(50)
            .setPatientCount(25).setHasTimestamp(true).setTimestampMin(1609459200000L) // 2021-01-01
            .setTimestampMax(1640995200000L); // 2022-01-01
        metadataMap.put("\\timestamped_concept\\", meta1);

        ColumnMeta meta2 =
            new ColumnMeta().setName("\\non_timestamped_concept\\").setWidthInBytes(1).setColumnOffset(0).setCategorical(false).setMin(5.0)
                .setMax(15.0).setAllObservationsOffset(300).setAllObservationsLength(400).setObservationCount(30).setPatientCount(15);
        // No timestamp fields set (defaults: hasTimestamp=false, min/max=null)
        metadataMap.put("\\non_timestamped_concept\\", meta2);

        // Write CSV
        Path csvPath = tempDir.resolve("columnMeta.csv");
        ColumnMetaBuilder.writeColumnMetaCsv(metadataMap, csvPath);

        // Verify file exists and has content
        assertTrue(Files.exists(csvPath));
        List<String> lines = Files.readAllLines(csvPath);
        assertEquals(2, lines.size(), "Should have 2 concept rows");

        // TreeMap sorts alphabetically: \non_timestamped... comes before \timestamped...
        // Line 0: non_timestamped_concept (no timestamps)
        // Line 1: timestamped_concept (with timestamps)

        String nonTimestampedLine = lines.get(0);
        assertTrue(nonTimestampedLine.contains("non_timestamped_concept"), "Line should contain non_timestamped_concept");
        assertTrue(nonTimestampedLine.contains("false"), "Line should contain hasTimestamp=false");

        String timestampedLine = lines.get(1);
        assertTrue(timestampedLine.contains("timestamped_concept"), "Line should contain timestamped_concept");
        assertTrue(timestampedLine.contains("true"), "Line should contain hasTimestamp=true");
        assertTrue(timestampedLine.contains("1609459200000"), "Line should contain timestampMin");
        assertTrue(timestampedLine.contains("1640995200000"), "Line should contain timestampMax");
    }

    @Test
    void testFromPhenoCube_Numeric_AllNegativeValuesHaveRealMax() {
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\negative\\", Double.class);
        cube.setSortedByKey(
            new KeyAndValue[] {new KeyAndValue<>(1, -10.5, null), new KeyAndValue<>(2, -2.5, null), new KeyAndValue<>(3, -7.0, null)}
        );

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        assertEquals(-10.5, meta.getMin(), 0.0);
        assertEquals(-2.5, meta.getMax(), 0.0);
    }

    @Test
    void testFromPhenoCube_Numeric_EmptyHasNaNMinMax() {
        PhenoCube<Double> cube = new PhenoCube<>("\\test\\none\\", Double.class);
        cube.setSortedByKey(new KeyAndValue[0]);

        ColumnMeta meta = ColumnMetaBuilder.fromPhenoCube(cube);

        assertTrue(Double.isNaN(meta.getMin()));
        assertTrue(Double.isNaN(meta.getMax()));
    }
}
