package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.ObservationRow;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The producer behaviours that differ from the original hpds-ingest: the epoch timestamp contract (A4), the both-values rule, the
 * single-chunk fallback and the failure handling of the parallel path (A5).
 */
class CsvProducerContractTest {

    private static final String HEADER = "PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n";

    @TempDir
    Path tempDir;

    private FailureSink failureSink;

    @BeforeEach
    void setUp() {
        failureSink = mock(FailureSink.class);
    }

    private List<FailureRecord> recordedFailures() {
        ArgumentCaptor<FailureRecord> captor = ArgumentCaptor.forClass(FailureRecord.class);
        verify(failureSink, atLeast(0)).recordFailure(captor.capture());
        return captor.getAllValues();
    }

    private List<ObservationRow> run(CsvObservationProducer producer, Path file) throws IOException {
        List<ObservationRow> rows = Collections.synchronizedList(new ArrayList<>());
        producer.processFile(file, rows::addAll, 100);
        return rows;
    }

    private CsvObservationProducer sequential() {
        return new CsvObservationProducer("test-run", failureSink);
    }

    /** Threshold of 1 byte forces the large-file path; the chunk size decides how many chunks come out of a small file. */
    private CsvObservationProducer parallel(long chunkSizeRows) {
        return new CsvObservationProducer("test-run", failureSink, 1, chunkSizeRows);
    }

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    // ---- A4: timestamps ------------------------------------------------------------------------------------------

    @Test
    void timestampColumnAcceptsEpochMillisecondsIsoOffsetZonelessAndDateOnly() throws IOException {
        Path file = write(
            "timestamps.csv",
            HEADER + "1,\\a\\,1,,1700000000000\n" + "2,\\a\\,2,,2021-01-15T10:30:00+02:00\n" + "3,\\a\\,3,,2021-01-15T10:30:00Z\n"
                + "4,\\a\\,4,,2023-11-25 14:00:00\n" + "5,\\a\\,5,,2025-02-28T12:56:40.500\n" + "6,\\a\\,6,,2025-02-28\n"
        );

        List<ObservationRow> rows = run(sequential(), file);

        assertEquals(6, rows.size());
        assertEquals(Instant.ofEpochMilli(1700000000000L), rows.get(0).dateTime());
        assertEquals(Instant.parse("2021-01-15T08:30:00Z"), rows.get(1).dateTime());
        assertEquals(Instant.parse("2021-01-15T10:30:00Z"), rows.get(2).dateTime());
        assertEquals(Instant.parse("2023-11-25T14:00:00Z"), rows.get(3).dateTime());
        assertEquals(Instant.parse("2025-02-28T12:56:40.500Z"), rows.get(4).dateTime());
        assertEquals(Instant.parse("2025-02-28T00:00:00Z"), rows.get(5).dateTime());
        verify(failureSink, never()).recordFailure(any());
    }

    @Test
    void zeroAndBlankTimestampsAreNullEvenInAHeaderlessFile() throws IOException {
        Path file = write("headerless.csv", "1,\\a\\,1,,0\n" + "2,\\a\\,2,,\n" + "3,\\a\\,3,\n" + "4,\\a\\,4,,1700000000000\n");

        List<ObservationRow> rows = run(sequential(), file);

        assertEquals(4, rows.size());
        assertNull(rows.get(0).dateTime());
        assertNull(rows.get(1).dateTime());
        assertNull(rows.get(2).dateTime());
        assertEquals(Instant.ofEpochMilli(1700000000000L), rows.get(3).dateTime());
        verify(failureSink, never()).recordFailure(any());
    }

    @Test
    void epochTimestampsSurviveTheParallelPathToo() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 2_000; i++) {
            csv.append(i).append(",\\a\\,").append(i).append(",,").append(1_700_000_000_000L + i).append('\n');
        }
        Path file = write("epoch-parallel.csv", csv.toString());

        List<ObservationRow> rows = run(parallel(100), file);

        assertEquals(2_000, rows.size());
        for (ObservationRow row : rows) {
            assertEquals(Instant.ofEpochMilli(1_700_000_000_000L + row.patientNum()), row.dateTime());
        }
    }

    // ---- A5a: rows with both values -------------------------------------------------------------------------------

    @Test
    void rowWithBothValuesKeepsTheNumberAndRecordsTheDiscardedText() throws IOException {
        Path file = write("both.csv", HEADER + "1,\\a\\,5,five,\n" + "2,\\a\\,6,,\n" + "3,\\a\\,,seven,\n");

        List<ObservationRow> rows = run(sequential(), file);

        assertEquals(3, rows.size());
        assertEquals(5.0, rows.get(0).numericValue());
        assertNull(rows.get(0).textValue());
        assertTrue(rows.stream().allMatch(ObservationRow::isValid), "every row handed to the writer carries exactly one value");

        List<FailureRecord> failures = recordedFailures();
        assertEquals(1, failures.size());
        FailureRecord failure = failures.get(0);
        assertEquals(FailureReason.BOTH_VALUES_TEXT_DISCARDED, failure.reasonCode());
        assertEquals(1, failure.patientNum());
        assertEquals("five", failure.valueRaw());
        assertEquals(1L, failure.recordNumber());
        assertEquals("both.csv", failure.dataset());
    }

    @Test
    void unparseableNumberWithTextKeepsTheTextAndRecordsTheFailure() throws IOException {
        Path file = write("badnum.csv", HEADER + "1,\\a\\,not-a-number,fallback,\n" + "2,\\a\\,not-a-number,,\n");

        List<ObservationRow> rows = run(sequential(), file);

        assertEquals(1, rows.size());
        assertEquals("fallback", rows.get(0).textValue());
        assertNull(rows.get(0).numericValue());
        List<FailureRecord> failures = recordedFailures();
        assertEquals(2, failures.size());
        assertTrue(failures.stream().allMatch(f -> f.reasonCode() == FailureReason.NUMERIC_PARSE_ERROR));
    }

    @Test
    void bothValuesFailuresAreCappedPerFileWithOneSummaryRecord() throws IOException {
        int rowsInFile = CsvObservationProducer.MAX_BOTH_VALUES_RECORDS_PER_FILE + 500;
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= rowsInFile; i++) {
            csv.append(i).append(",\\a\\,1,t,\n");
        }
        Path file = write("many-both.csv", csv.toString());

        List<ObservationRow> rows = run(sequential(), file);

        assertEquals(rowsInFile, rows.size(), "no row is lost to the cap, only its individual failure record");
        List<FailureRecord> failures = recordedFailures();
        assertEquals(CsvObservationProducer.MAX_BOTH_VALUES_RECORDS_PER_FILE + 1, failures.size());
        FailureRecord summary = failures.get(failures.size() - 1);
        assertNull(summary.patientNum());
        assertTrue(summary.reasonDetail().contains(String.valueOf(rowsInFile)), summary.reasonDetail());
        assertTrue(summary.reasonDetail().contains("500 more"), summary.reasonDetail());
    }

    // ---- A5b: single-chunk file above the size threshold ---------------------------------------------------------------

    @Test
    void largeFileThatYieldsOneChunkFallsBackToSequentialInsteadOfRecursing() throws IOException {
        Path file = write("one-chunk.csv", HEADER + "1,\\a\\,1,,\n" + "2,\\a\\,2,,\n" + "3,\\a\\,3,,\n");
        // threshold 1 byte: the file counts as "large"; default 1M rows per chunk: it yields a single chunk

        List<ObservationRow> rows = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> run(parallel(1_000_000), file));

        assertEquals(3, rows.size());
        verify(failureSink, never()).recordFailure(any());
    }

    @Test
    void largeFileOfLongRowsThatYieldsOneChunkFallsBackToSequential() throws IOException {
        String longText = "x".repeat(2_000);
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 50; i++) {
            csv.append(i).append(",\\a\\,,").append(longText).append(",\n");
        }
        Path file = write("long-rows.csv", csv.toString());
        // 100 KB file against a 50 KB threshold, but 50 rows of the 1M-row chunk size is one chunk

        List<ObservationRow> rows = assertTimeoutPreemptively(
            Duration.ofSeconds(20), () -> run(new CsvObservationProducer("test-run", failureSink, 50_000, 1_000_000), file)
        );

        assertEquals(50, rows.size());
        assertEquals(longText, rows.get(49).textValue());
    }

    @Test
    void parallelPathProducesEveryRowExactlyOnce() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 5_000; i++) {
            csv.append(i).append(",\\c").append(i % 7).append("\\,").append(i).append(",,\n");
        }
        Path file = write("many-chunks.csv", csv.toString());

        List<ObservationRow> rows = run(parallel(200), file);

        assertEquals(5_000, rows.size());
        Set<Integer> patients = rows.stream().map(ObservationRow::patientNum).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(5_000, patients.size(), "no row duplicated or lost at a chunk boundary");
        assertEquals(1, patients.iterator().next());
        verify(failureSink, never()).recordFailure(any());
    }

    // ---- A5c: parse errors in the parallel path ---------------------------------------------------------------------

    /** Throws from parseRecord for one patient, to exercise the generic error handling around it. */
    private static class ExplodingProducer extends CsvObservationProducer {
        ExplodingProducer(FailureSink sink, long thresholdBytes, long chunkRows) {
            super("test-run", sink, thresholdBytes, chunkRows);
        }

        @Override
        ObservationRow parseRecord(CSVRecord record, Path filePath, boolean useHeaders) {
            if (record.get(0).equals("3")) {
                throw new IllegalStateException("boom");
            }
            return super.parseRecord(record, filePath, useHeaders);
        }
    }

    private Path fiveHundredRows() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 500; i++) {
            csv.append(i).append(",\\a\\,").append(i).append(",,\n");
        }
        return write("explode.csv", csv.toString());
    }

    @Test
    void unexpectedParseErrorIsRecordedInTheSequentialPath() throws IOException {
        List<ObservationRow> rows = run(new ExplodingProducer(failureSink, Long.MAX_VALUE, 100), fiveHundredRows());

        assertEquals(499, rows.size());
        List<FailureRecord> failures = recordedFailures();
        assertEquals(1, failures.size());
        assertEquals(FailureReason.UNKNOWN, failures.get(0).reasonCode());
        assertTrue(failures.get(0).reasonDetail().contains("boom"));
    }

    @Test
    void unexpectedParseErrorIsRecordedTheSameWayInTheParallelPath() throws IOException {
        List<ObservationRow> rows = run(new ExplodingProducer(failureSink, 1, 50), fiveHundredRows());

        assertEquals(499, rows.size());
        List<FailureRecord> failures = recordedFailures();
        assertEquals(1, failures.size(), "the parallel path must not just log and move on");
        assertEquals(FailureReason.UNKNOWN, failures.get(0).reasonCode());
        assertTrue(failures.get(0).reasonDetail().contains("boom"));
        assertEquals("explode.csv", failures.get(0).dataset());
    }

    @Test
    void aWriterFailureIsNotMistakenForAParseErrorAndStopsTheFile() throws IOException {
        Path file = fiveHundredRows();
        Consumer<List<ObservationRow>> failingWriter = rows -> {
            throw new IllegalStateException("disk full");
        };

        assertThrows(
            IllegalStateException.class, () -> new CsvObservationProducer("test-run", failureSink).processFile(file, failingWriter, 100)
        );
        verify(failureSink, never()).recordFailure(any());
    }

    @Test
    void aWriterFailureInTheParallelPathFailsTheFileInsteadOfReportingSuccess() throws IOException {
        Path file = fiveHundredRows();
        Consumer<List<ObservationRow>> failingWriter = rows -> {
            throw new IllegalStateException("disk full");
        };

        IOException e = assertThrows(IOException.class, () -> parallel(50).processFile(file, failingWriter, 100));
        assertTrue(e.getMessage().contains("chunks"), e.getMessage());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        // the failed chunks are also visible in the failures report
        assertTrue(recordedFailures().stream().anyMatch(f -> f.reasonCode() == FailureReason.FILE_READ_ERROR));
    }
}
