package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.FailureSink;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.ObservationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for CSV processing - tests end-to-end CSV ingestion with real data.
 *
 * These tests use real CSV files from the input directory if available. Set system property -Dtest.integration.csv.path=/path/to/csv to
 * test with specific files.
 */
class CsvProcessingIntegrationTest {

    @TempDir
    Path tempDir;

    private CsvObservationProducer producer;
    private FailureSink mockFailureSink;
    private String runId;

    @BeforeEach
    void setUp() {
        runId = "integration-test-" + System.currentTimeMillis();
        mockFailureSink = mock(FailureSink.class);
        producer = new CsvObservationProducer(runId, mockFailureSink);
    }

    @Test
    void testEndToEnd_SmallCsv_SequentialProcessing() throws IOException {
        // Create a realistic small CSV file
        Path csvFile = tempDir.resolve("small-allConcepts.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            62577,\\DCC Harmonized data set\\atherosclerosis\\age_at_cac_score_1\\,48.13,,0
            62578,\\DCC Harmonized data set\\atherosclerosis\\age_at_cac_score_1\\,56.02,,0
            62581,\\DCC Harmonized data set\\atherosclerosis\\age_at_cac_score_1\\,46,,0
            62600,\\DCC Harmonized data set\\demographics\\age\\,65.8,,0
            62601,\\DCC Harmonized data set\\demographics\\age\\,52.87,,0
            62602,\\DCC Harmonized data set\\demographics\\sex\\,,Male,0
            62606,\\DCC Harmonized data set\\demographics\\sex\\,,Female,0
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        AtomicInteger batchCount = new AtomicInteger(0);

        Consumer<List<ObservationRow>> consumer = rows -> {
            batchCount.incrementAndGet();
            allRows.addAll(rows);
        };

        producer.processFile(csvFile, consumer, 1000);

        // Assertions
        assertEquals(7, allRows.size(), "Should process all 7 rows");
        assertEquals(1, batchCount.get(), "Should process in 1 batch (batch size 1000)");

        // Verify data integrity
        ObservationRow firstRow = allRows.get(0);
        assertEquals(62577, firstRow.patientNum());
        assertEquals("\\DCC Harmonized data set\\atherosclerosis\\age_at_cac_score_1\\", firstRow.conceptPath());
        assertEquals(48.13, firstRow.numericValue(), 0.001);
        assertNull(firstRow.textValue());

        // Verify text value row
        ObservationRow textRow =
            allRows.stream().filter(r -> r.textValue() != null && r.textValue().equals("Male")).findFirst().orElse(null);
        assertNotNull(textRow, "Should find Male text value");
        assertEquals(62602, textRow.patientNum());

        // No failures should be recorded
        verify(mockFailureSink, never()).recordFailure(any());
    }

    @Test
    void testEndToEnd_LargeCsv_ParallelProcessing() throws IOException {
        // Create a large CSV file (> 500MB threshold for parallel processing)
        // Since we can't create 500MB in unit tests, we'll create a file with 100K rows
        Path csvFile = tempDir.resolve("large-allConcepts.csv");

        // Write header
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n");

        // Generate 100,000 rows
        for (int i = 1; i <= 100_000; i++) {
            int patientNum = 60000 + i;
            String conceptPath = "\\DCC Harmonized data set\\test\\concept_" + (i % 100) + "\\";
            double nvalNum = i * 1.5;
            String timestamp = "0";

            csvContent.append(patientNum).append(",").append(conceptPath).append(",").append(nvalNum).append(",,").append(timestamp)
                .append("\n");
        }

        Files.writeString(csvFile, csvContent.toString());

        List<ObservationRow> allRows = new ArrayList<>();
        AtomicInteger batchCount = new AtomicInteger(0);
        ConcurrentHashMap<Integer, ObservationRow> rowMap = new ConcurrentHashMap<>();

        Consumer<List<ObservationRow>> consumer = rows -> {
            batchCount.incrementAndGet();
            synchronized (allRows) {
                allRows.addAll(rows);
            }
            rows.forEach(row -> rowMap.put(row.patientNum(), row));
        };

        long startTime = System.currentTimeMillis();
        producer.processFile(csvFile, consumer, 1000);
        long duration = System.currentTimeMillis() - startTime;

        // Assertions
        assertEquals(100_000, allRows.size(), "Should process all 100,000 rows");
        assertTrue(batchCount.get() >= 100, "Should process in multiple batches");

        // Verify no duplicates
        assertEquals(100_000, rowMap.size(), "Should have unique patient numbers");

        // Verify data integrity - sample random rows
        ObservationRow row5000 = rowMap.get(65000);
        assertNotNull(row5000, "Should find patient 65000");
        assertEquals(65000, row5000.patientNum());
        assertTrue(row5000.conceptPath().startsWith("\\DCC Harmonized data set\\"));

        System.out.println("Processed 100,000 rows in " + duration + "ms (" + (100_000.0 / duration * 1000) + " rows/sec)");

        // No failures should be recorded
        verify(mockFailureSink, never()).recordFailure(any());
    }

    @Test
    void testEndToEnd_WithInvalidRows_RecordsFailures() throws IOException {
        Path csvFile = tempDir.resolve("with-errors.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            62577,\\valid\\path\\,100,,0
            invalid-id,\\valid\\path\\,100,,0
            62578,,100,,0
            62579,\\valid\\path\\,,,0
            62580,\\valid\\path\\,not-a-number,,0
            62581,\\valid\\path\\,200,text,0
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        // Should process only valid rows
        assertEquals(2, allRows.size(), "Should process only 2 valid rows");

        // Should record 4 rejected rows, plus the discarded text value of the row with both values
        verify(mockFailureSink, times(5)).recordFailure(any());

        // Verify valid rows
        assertEquals(62577, allRows.get(0).patientNum());
        assertEquals(62581, allRows.get(1).patientNum());
    }

    @Test
    void testEndToEnd_MixedDataTypes_ParsesCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("mixed-types.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            1,\\numeric\\only\\,100.5,,
            2,\\text\\only\\,,Category A,
            3,\\both\\values\\,99.9,Status,
            4,\\with\\timestamp\\,75.0,,2024-01-15T10:30:00Z
            5,\\integer\\,42,,
            6,\\negative\\,-123.45,,
            7,\\zero\\,0,,
            8,\\decimal\\,0.001,,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(8, allRows.size());

        // Verify numeric only
        assertEquals(100.5, allRows.get(0).numericValue());
        assertNull(allRows.get(0).textValue());

        // Verify text only
        assertNull(allRows.get(1).numericValue());
        assertEquals("Category A", allRows.get(1).textValue());

        // Verify both: the numeric value wins
        assertEquals(99.9, allRows.get(2).numericValue());
        assertNull(allRows.get(2).textValue());

        // Verify timestamp
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), allRows.get(3).dateTime());

        // Verify negative
        assertEquals(-123.45, allRows.get(5).numericValue());

        // Verify zero
        assertEquals(0.0, allRows.get(6).numericValue());

        // Verify small decimal
        assertEquals(0.001, allRows.get(7).numericValue(), 0.0001);
    }

    @Test
    void testEndToEnd_MultipleConceptPaths_GroupsCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("multi-concepts.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n");

        // Create data for 10 patients across 5 different concept paths
        for (int patientId = 1; patientId <= 10; patientId++) {
            for (int conceptId = 1; conceptId <= 5; conceptId++) {
                csvContent.append(patientId).append(",\\concept\\path_").append(conceptId).append("\\,").append(patientId * conceptId)
                    .append(",,0\n");
            }
        }
        Files.writeString(csvFile, csvContent.toString());

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        // Should have 50 total observations (10 patients * 5 concepts)
        assertEquals(50, allRows.size());

        // Group by patient
        Map<Integer, List<ObservationRow>> byPatient = allRows.stream().collect(Collectors.groupingBy(ObservationRow::patientNum));
        assertEquals(10, byPatient.size(), "Should have 10 unique patients");

        // Each patient should have 5 observations
        byPatient.values().forEach(rows -> assertEquals(5, rows.size(), "Each patient should have 5 concept observations"));

        // Group by concept path
        Map<String, List<ObservationRow>> byConcept = allRows.stream().collect(Collectors.groupingBy(ObservationRow::conceptPath));
        assertEquals(5, byConcept.size(), "Should have 5 unique concept paths");

        // Each concept should have 10 observations (one per patient)
        byConcept.values().forEach(rows -> assertEquals(10, rows.size(), "Each concept should have 10 patient observations"));
    }

    @Test
    @EnabledIfSystemProperty(named = "test.integration.csv.path", matches = ".+")
    void testEndToEnd_RealDataFile_ProcessesSuccessfully() throws IOException {
        String csvPath = System.getProperty("test.integration.csv.path");
        Path csvFile = Paths.get(csvPath);

        if (!Files.exists(csvFile)) {
            System.out.println("Skipping real data test - file not found: " + csvPath);
            return;
        }

        long fileSize = Files.size(csvFile);
        System.out.println("Testing with real CSV file: " + csvFile);
        System.out.println("File size: " + (fileSize / 1024 / 1024) + " MB");

        List<ObservationRow> allRows = new ArrayList<>();
        AtomicInteger batchCount = new AtomicInteger(0);

        Consumer<List<ObservationRow>> consumer = rows -> {
            batchCount.incrementAndGet();
            synchronized (allRows) {
                allRows.addAll(rows);
            }
        };

        long startTime = System.currentTimeMillis();
        producer.processFile(csvFile, consumer, 10000);
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("Processed " + allRows.size() + " rows in " + duration + "ms");
        System.out.println("Throughput: " + (allRows.size() / (duration / 1000.0)) + " rows/sec");
        System.out.println("Batches: " + batchCount.get());

        // Basic assertions
        assertTrue(allRows.size() > 0, "Should process at least some rows");
        assertTrue(duration > 0, "Should take some time to process");

        // Verify no null values in required fields
        allRows.forEach(row -> {
            assertNotNull(row.patientNum(), "Patient num should not be null");
            assertNotNull(row.conceptPath(), "Concept path should not be null");
            assertTrue(row.numericValue() != null || row.textValue() != null, "Should have at least one value type");
        });
    }

    @Test
    void testEndToEnd_ConcurrentProcessing_NoDataLoss() throws IOException {
        // Create moderate-sized file
        Path csvFile = tempDir.resolve("concurrent-test.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n");

        for (int i = 1; i <= 10_000; i++) {
            csvContent.append(i).append(",\\test\\concept\\,").append(i * 1.0).append(",,0\n");
        }
        Files.writeString(csvFile, csvContent.toString());

        // Use thread-safe collections
        ConcurrentHashMap<Integer, ObservationRow> rowMap = new ConcurrentHashMap<>();
        AtomicInteger totalRows = new AtomicInteger(0);

        Consumer<List<ObservationRow>> consumer = rows -> {
            totalRows.addAndGet(rows.size());
            rows.forEach(row -> {
                ObservationRow previous = rowMap.put(row.patientNum(), row);
                assertNull(previous, "Should not have duplicate patient numbers");
            });
        };

        producer.processFile(csvFile, consumer, 1000);

        // Verify no data loss
        assertEquals(10_000, totalRows.get(), "Should process all 10,000 rows");
        assertEquals(10_000, rowMap.size(), "Should have 10,000 unique patient numbers");

        // Verify sequential patient numbers
        for (int i = 1; i <= 10_000; i++) {
            assertTrue(rowMap.containsKey(i), "Should contain patient " + i);
        }
    }

    @Test
    void testEndToEnd_EhrFormatFile_ParsesTimestamps() throws IOException {
        // EHR export format shape: alias header names, µ-delimited concept paths,
        // space-separated zoneless timestamps (synthetic data)
        Path csvFile = tempDir.resolve("ehr-format.csv");
        String csvContent = """
            subject_id,concept_path,continuous_nval,categorical_tval,timestamp_ts
            101,µteststudyµcohortµEHRµConditionOccurenceµFake Condition(S-00001)µ,,00001 fake diagnosis A,2023-11-25 14:00:00
            101,µteststudyµcohortµEHRµConditionOccurenceµFake Condition(S-00001)µ,,00002 fake diagnosis B,2023-11-27 00:00:00
            102,µteststudyµcohortµEHRµConditionOccurenceµFake Condition(S-00001)µ,,00001 fake diagnosis A,2024-06-27 15:00:00
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(3, allRows.size(), "Header line must be detected, all data rows ingested");

        ObservationRow row1 = allRows.get(0);
        assertEquals(101, row1.patientNum());
        assertEquals("00001 fake diagnosis A", row1.textValue());
        assertEquals(Instant.parse("2023-11-25T14:00:00Z"), row1.dateTime(), "Space-separated timestamp must be ingested as UTC");
        assertEquals(Instant.parse("2023-11-27T00:00:00Z"), allRows.get(1).dateTime());
        assertEquals(Instant.parse("2024-06-27T15:00:00Z"), allRows.get(2).dateTime());

        // Every row must carry a non-null timestamp
        allRows.forEach(row -> assertNotNull(row.dateTime()));

        // No failures (header line must not be recorded as INVALID_PATIENT_ID)
        verify(mockFailureSink, never()).recordFailure(any());
    }
}
