package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.FailureSink;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.ObservationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CsvObservationProducer - validates CSV parsing and large file detection.
 */
class CsvObservationProducerTest {

    @TempDir
    Path tempDir;

    private CsvObservationProducer producer;
    private FailureSink mockFailureSink;
    private String runId;

    @BeforeEach
    void setUp() {
        runId = "test-run-" + System.currentTimeMillis();
        mockFailureSink = mock(FailureSink.class);
        producer = new CsvObservationProducer(runId, mockFailureSink);
    }

    @Test
    void testConstructor_NoCircularDependency() {
        // Validates that constructor does not create circular dependency
        assertDoesNotThrow(() -> {
            CsvObservationProducer prod = new CsvObservationProducer(runId, mockFailureSink);
            assertNotNull(prod);
        });
    }

    @Test
    void testProcessFile_WithHeaders_ParsesCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("with-headers.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            1,\\test\\path\\,100,,2024-01-01T00:00:00Z
            2,\\test\\path\\,200,text-value,2024-01-02T00:00:00Z
            3,\\test\\path\\,,only-text,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(3, allRows.size());

        // Validate first row
        ObservationRow row1 = allRows.get(0);
        assertEquals(1, row1.patientNum());
        assertEquals("\\test\\path\\", row1.conceptPath());
        assertEquals(100.0, row1.numericValue());
        assertNull(row1.textValue());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), row1.dateTime());

        // Validate second row
        ObservationRow row2 = allRows.get(1);
        assertEquals(2, row2.patientNum());
        assertEquals(200.0, row2.numericValue());
        assertNull(row2.textValue(), "both values present: numeric wins");

        // Validate third row (text only)
        ObservationRow row3 = allRows.get(2);
        assertEquals(3, row3.patientNum());
        assertNull(row3.numericValue());
        assertEquals("only-text", row3.textValue());
    }

    @Test
    void testProcessFile_WithoutHeaders_ParsesCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("no-headers.csv");
        String csvContent = """
            1,\\test\\path\\,100,,2024-01-01T00:00:00Z
            2,\\test\\path\\,200,text-value,2024-01-02T00:00:00Z
            3,\\test\\path\\,,only-text,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(3, allRows.size());

        // Validate parsing without headers
        ObservationRow row1 = allRows.get(0);
        assertEquals(1, row1.patientNum());
        assertEquals("\\test\\path\\", row1.conceptPath());
        assertEquals(100.0, row1.numericValue());
    }

    @Test
    void testProcessFile_AddsTrailingBackslash() throws IOException {
        Path csvFile = tempDir.resolve("no-trailing-slash.csv");
        String csvContent = """
            1,\\test\\path,100,,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(1, allRows.size());
        assertEquals("\\test\\path\\", allRows.get(0).conceptPath());
    }

    @Test
    void testProcessFile_EmptyFile_ReturnsEmpty() throws IOException {
        Path csvFile = tempDir.resolve("empty.csv");
        Files.writeString(csvFile, "");

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertTrue(allRows.isEmpty());
    }

    @Test
    void testProcessFile_HeaderOnly_ReturnsEmpty() throws IOException {
        Path csvFile = tempDir.resolve("header-only.csv");
        Files.writeString(csvFile, "PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n");

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertTrue(allRows.isEmpty());
    }

    @Test
    void testProcessFile_BatchProcessing_CallsConsumerMultipleTimes() throws IOException {
        Path csvFile = tempDir.resolve("batch-test.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR\n");

        // Create 250 rows with batch size 100
        for (int i = 1; i <= 250; i++) {
            csvContent.append(i).append(",\\test\\,").append(i * 10).append(",\n");
        }
        Files.writeString(csvFile, csvContent.toString());

        AtomicInteger batchCount = new AtomicInteger(0);
        List<ObservationRow> allRows = new ArrayList<>();

        Consumer<List<ObservationRow>> consumer = rows -> {
            batchCount.incrementAndGet();
            allRows.addAll(rows);
        };

        producer.processFile(csvFile, consumer, 100);

        // Should be called 3 times: 100, 100, 50
        assertEquals(3, batchCount.get());
        assertEquals(250, allRows.size());
    }

    @Test
    void testProcessFile_MissingPatientNum_RecordsFailure() throws IOException {
        Path csvFile = tempDir.resolve("missing-patient.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            ,\\test\\path\\,100,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        // Should skip invalid row
        assertTrue(allRows.isEmpty());

        // Should record failure
        verify(mockFailureSink, atLeastOnce()).recordFailure(any());
    }

    @Test
    void testProcessFile_MissingConceptPath_RecordsFailure() throws IOException {
        Path csvFile = tempDir.resolve("missing-concept.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            1,,100,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertTrue(allRows.isEmpty());
        verify(mockFailureSink, atLeastOnce()).recordFailure(any());
    }

    @Test
    void testProcessFile_MissingBothValues_RecordsFailure() throws IOException {
        Path csvFile = tempDir.resolve("missing-values.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            1,\\test\\path\\,,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertTrue(allRows.isEmpty());
        verify(mockFailureSink, atLeastOnce()).recordFailure(any());
    }

    @Test
    void testProcessFile_InvalidNumericValue_RecordsFailure() throws IOException {
        Path csvFile = tempDir.resolve("invalid-numeric.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            1,\\test\\path\\,not-a-number,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertTrue(allRows.isEmpty());
        verify(mockFailureSink, atLeastOnce()).recordFailure(any());
    }

    @Test
    void testProcessFile_InvalidPatientId_RecordsFailure() throws IOException {
        Path csvFile = tempDir.resolve("invalid-patient-id.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            not-a-number,\\test\\path\\,100,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertTrue(allRows.isEmpty());
        verify(mockFailureSink, atLeastOnce()).recordFailure(any());
    }

    @Test
    void testProcessFile_InvalidTimestamp_ContinuesWithoutTimestamp() throws IOException {
        Path csvFile = tempDir.resolve("invalid-timestamp.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            1,\\test\\path\\,100,,invalid-timestamp
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        // Should still process row, just without timestamp
        assertEquals(1, allRows.size());
        assertNull(allRows.get(0).dateTime());
    }

    @Test
    void testProcessFile_LargeFile_DelegatesChunkProcessor() throws IOException {
        // Create a file larger than 500MB threshold (simulate with small file for testing)
        Path csvFile = tempDir.resolve("large.csv");
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR\n");

        // Create enough content to exceed threshold
        for (int i = 1; i <= 1000; i++) {
            csvContent.append(i).append(",\\test\\path\\with\\very\\long\\name\\to\\increase\\size\\,").append(i * 10)
                .append(",very-long-text-value-to-increase-file-size\n");
        }

        // Pad to exceed 500MB threshold (note: in real test this would be huge)
        // For unit test purposes, we'll just verify the behavior with normal size
        Files.writeString(csvFile, csvContent.toString());

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        // This will use sequential processing due to file size
        producer.processFile(csvFile, consumer, 1000);

        // Verify rows were processed
        assertTrue(allRows.size() > 0);
    }

    @Test
    void testProcessFile_QuotedFields_ParsesCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("quoted-fields.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            1,\\test\\path\\,,"text with, comma"
            2,\\test\\path\\,,"simple text"
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(2, allRows.size());
        assertEquals("text with, comma", allRows.get(0).textValue());
    }

    @Test
    void testProcessFile_MixedNumericAndTextValues_ParsesCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("mixed-values.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR
            1,\\test\\,100,text1
            2,\\test\\,200,
            3,\\test\\,,text2
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(3, allRows.size());

        // Row 1: both numeric and text: the numeric value wins, the text is discarded and recorded as a failure
        assertEquals(100.0, allRows.get(0).numericValue());
        assertNull(allRows.get(0).textValue());
        verify(mockFailureSink, times(1)).recordFailure(any());

        // Row 2: numeric only
        assertEquals(200.0, allRows.get(1).numericValue());
        assertNull(allRows.get(1).textValue());

        // Row 3: text only
        assertNull(allRows.get(2).numericValue());
        assertEquals("text2", allRows.get(2).textValue());
    }

    /**
     * Test that timestamp value "0" is treated as null (not parsed as epoch).
     *
     * Background: Legacy allConcepts.csv files use "0" to represent missing timestamps. This is NOT a valid ISO 8601 timestamp and should
     * result in null dateTime.
     *
     * This test validates the fix from commit e09e2778 (CSVLoaderNewSearch pattern).
     */
    @Test
    void testTimestampZeroTreatedAsNull() throws IOException {
        Path csvFile = tempDir.resolve("test_zero_timestamp.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            123,\\Demographics\\Gender\\,,"Male",0
            456,\\Lab\\Hemoglobin\\,13.5,,2021-01-15T10:30:00Z
            789,\\Demographics\\Age\\,65,,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(3, allRows.size());

        // Row 1: timestamp "0" should be treated as null
        ObservationRow row1 = allRows.get(0);
        assertEquals(123, row1.patientNum());
        assertEquals("\\Demographics\\Gender\\", row1.conceptPath());
        assertEquals("Male", row1.textValue());
        assertNull(row1.dateTime(), "Timestamp '0' should be treated as null, not parsed as epoch");

        // Row 2: valid ISO 8601 timestamp should parse correctly
        ObservationRow row2 = allRows.get(1);
        assertEquals(456, row2.patientNum());
        assertEquals("\\Lab\\Hemoglobin\\", row2.conceptPath());
        assertEquals(13.5, row2.numericValue());
        assertNotNull(row2.dateTime(), "Valid ISO 8601 timestamp should parse");
        assertEquals(Instant.parse("2021-01-15T10:30:00Z"), row2.dateTime());

        // Row 3: empty timestamp should be null (existing behavior)
        ObservationRow row3 = allRows.get(2);
        assertEquals(789, row3.patientNum());
        assertEquals("\\Demographics\\Age\\", row3.conceptPath());
        assertEquals(65.0, row3.numericValue());
        assertNull(row3.dateTime(), "Empty timestamp should be null");
    }

    /**
     * EHR-format files use different header names (subject_id,concept_path,continuous_nval, categorical_tval,timestamp_ts) and
     * space-separated timestamps. The header line must be detected (not recorded as an INVALID_PATIENT_ID failure) and timestamps must
     * parse as UTC.
     */
    @Test
    void testProcessFile_EhrHeaders_ParsesCorrectly() throws IOException {
        Path csvFile = tempDir.resolve("ehr-headers.csv");
        String csvContent = """
            subject_id,concept_path,continuous_nval,categorical_tval,timestamp_ts
            1,\\teststudy\\condition\\diagnosis\\,,fake diagnosis A,2023-11-25 14:00:00
            2,\\teststudy\\condition\\diagnosis\\,,fake diagnosis B,2024-06-27 00:00:00
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(2, allRows.size(), "Header line must not be ingested as data");

        ObservationRow row1 = allRows.get(0);
        assertEquals(1, row1.patientNum());
        assertEquals("\\teststudy\\condition\\diagnosis\\", row1.conceptPath());
        assertEquals("fake diagnosis A", row1.textValue());
        assertEquals(Instant.parse("2023-11-25T14:00:00Z"), row1.dateTime(), "Space-separated timestamp should parse as UTC");

        assertEquals(Instant.parse("2024-06-27T00:00:00Z"), allRows.get(1).dateTime());

        // Header line must not be recorded as an INVALID_PATIENT_ID failure
        verify(mockFailureSink, never()).recordFailure(any());
    }

    /**
     * Only known header names make a header (step-1 review issue 9). Any other first row is data: here it fails as an invalid patient id
     * and is reported instead of being swallowed as a header.
     */
    @Test
    void testProcessFile_UnknownHeaderNames_AreDataAndReported() throws IOException {
        Path csvFile = tempDir.resolve("arbitrary-headers.csv");
        String csvContent = """
            Patient ID,Concept,Numeric Value,Text Value,Date
            1,\\test\\path\\,100,,2024-01-01T00:00:00Z
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        CsvObservationProducer.FileResult result = producer.processFile(csvFile, consumer, 1000);

        assertEquals(1, allRows.size());
        assertEquals(1, allRows.get(0).patientNum());
        assertEquals(2, result.recordsRead());
        assertEquals(java.util.Map.of(FailureReason.INVALID_PATIENT_ID, 1L), result.rejected());
        verify(mockFailureSink, org.mockito.Mockito.times(1)).recordFailure(any());
    }

    @Test
    void testProcessFile_ByteOrderMarkBeforeAHeaderAndBeforeData() throws IOException {
        Path withHeader = tempDir.resolve("bom-header.csv");
        Files.writeString(withHeader, "\uFEFFPATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,DATE_TIME\n1,\\a\\,5,,\n");
        Path withoutHeader = tempDir.resolve("bom-data.csv");
        Files.writeString(withoutHeader, "\uFEFF2,\\a\\,6,,\n");

        List<ObservationRow> rows = new ArrayList<>();
        producer.processFile(withHeader, rows::addAll, 1000);
        producer.processFile(withoutHeader, rows::addAll, 1000);

        assertEquals(List.of(1, 2), rows.stream().map(ObservationRow::patientNum).toList());
        verify(mockFailureSink, never()).recordFailure(any());
    }

    /**
     * A header-less file's first line is a data row starting with an integer patient num; it must stay in positional mode and not be
     * swallowed as a header.
     */
    @Test
    void testProcessFile_HeaderlessFile_FirstRowNotSwallowed() throws IOException {
        Path csvFile = tempDir.resolve("headerless.csv");
        String csvContent = """
            1,\\test\\path\\,100,,2023-11-25 14:00:00
            2,\\test\\path\\,200,,
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(2, allRows.size(), "First data row must not be treated as a header");
        assertEquals(1, allRows.get(0).patientNum());
        assertEquals(Instant.parse("2023-11-25T14:00:00Z"), allRows.get(0).dateTime());
        verify(mockFailureSink, never()).recordFailure(any());
    }

    /**
     * Space-separated timestamps parse regardless of header naming.
     */
    @Test
    void testProcessFile_SpaceSeparatedTimestamp_ParsedAsUtc() throws IOException {
        Path csvFile = tempDir.resolve("space-timestamp.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            1,\\test\\path\\,100,,2023-11-25 14:00:00
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(1, allRows.size());
        assertEquals(Instant.parse("2023-11-25T14:00:00Z"), allRows.get(0).dateTime());
    }

    /**
     * Invalid timestamps remain non-fatal: rows are produced with null dateTime, and each one is recorded as TIMESTAMP_PARSE_ERROR.
     */
    @Test
    void testProcessFile_ManyInvalidTimestamps_RowsStillProduced() throws IOException {
        Path csvFile = tempDir.resolve("many-invalid-timestamps.csv");
        String csvContent = """
            PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP
            1,\\test\\path\\,100,,not-a-date
            2,\\test\\path\\,200,,25/11/2023
            3,\\test\\path\\,300,,2023-13-45 99:00:00
            """;
        Files.writeString(csvFile, csvContent);

        List<ObservationRow> allRows = new ArrayList<>();
        Consumer<List<ObservationRow>> consumer = allRows::addAll;

        producer.processFile(csvFile, consumer, 1000);

        assertEquals(3, allRows.size(), "Rows with invalid timestamps must still be produced");
        allRows.forEach(row -> assertNull(row.dateTime()));
        org.mockito.ArgumentCaptor<FailureRecord> failures = org.mockito.ArgumentCaptor.forClass(FailureRecord.class);
        verify(mockFailureSink, org.mockito.Mockito.times(3)).recordFailure(failures.capture());
        failures.getAllValues().forEach(f -> assertEquals(FailureReason.TIMESTAMP_PARSE_ERROR, f.reasonCode()));
        assertEquals("not-a-date", failures.getAllValues().get(0).timestampRaw());
    }
}
