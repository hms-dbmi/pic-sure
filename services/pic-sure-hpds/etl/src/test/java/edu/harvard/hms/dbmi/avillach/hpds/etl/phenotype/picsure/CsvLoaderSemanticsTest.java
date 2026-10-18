package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.CsvObservationProducer.FileResult;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.ObservationRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The row semantics PICSURELoader takes from {@code CSVLoader} (decision 1): path normalisation, the text equal to the last segment,
 * numeric-looking text, and the header rule. Test accessions only: phs000000, phs999999, phs123456.
 */
class CsvLoaderSemanticsTest {

    @TempDir
    Path tempDir;

    private List<ObservationRow> rows(String... lines) throws IOException {
        return rows(String.join("\n", lines) + "\n");
    }

    private List<ObservationRow> rows(String content) throws IOException {
        Path file = tempDir.resolve("in-" + System.nanoTime() + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        List<ObservationRow> rows = new CopyOnWriteArrayList<>();
        try (FailureSink sink = new FailureSink(tempDir.resolve("f-" + System.nanoTime() + ".jsonl"))) {
            new CsvObservationProducer("run", sink).processFile(file, rows::addAll, 100);
        }
        return new ArrayList<>(rows);
    }

    @Test
    void conceptPathIsNormalisedLikeCsvLoader() {
        assertEquals("\\a\\b\\", CsvObservationProducer.normalizeConceptPath("\\a\\b\\"));
        assertEquals("\\a\\b\\", CsvObservationProducer.normalizeConceptPath("\\a\\b"), "a trailing backslash is added");
        assertEquals("\\a\\b\\", CsvObservationProducer.normalizeConceptPath("\\ a \\ b \\ "), "segments are trimmed");
        assertEquals("\\a\\b\\", CsvObservationProducer.normalizeConceptPath("µaµbµ"), "µ is a backslash");
        assertEquals("\\phs000000\\Labs\\Glucose\\", CsvObservationProducer.normalizeConceptPath("µphs000000µLabsµGlucoseµ"));
        assertEquals("\\a\\b\\", CsvObservationProducer.normalizeConceptPath("\\a\\b�\\"), "U+FFFD is removed");
        assertEquals("\\a\\b\\", CsvObservationProducer.normalizeConceptPath("\\a\\ � b\\"), "and the trim runs again after that");
        assertEquals("a\\b\\", CsvObservationProducer.normalizeConceptPath("a\\b"), "no leading backslash is invented");
        assertEquals(
            "\\a\\\\b\\", CsvObservationProducer.normalizeConceptPath("\\a\\\\b\\"), "an empty middle segment stays, as in CSVLoader"
        );
    }

    @Test
    void textEqualToTheLastPathSegmentIsDroppedFromThePath() throws IOException {
        List<ObservationRow> rows = rows(
            "1,\\phs000000\\Smoking\\Yes\\,,Yes,", "2,\\phs000000\\Smoking\\,,Never,", "3,\\phs000000\\Smoking\\No\\,,No,",
            "4,\\phs000000\\Smoking\\Yes\\,,yes,"
        );

        assertEquals(
            List.of("\\phs000000\\Smoking\\", "\\phs000000\\Smoking\\", "\\phs000000\\Smoking\\", "\\phs000000\\Smoking\\Yes\\"),
            rows.stream().map(ObservationRow::conceptPath).toList()
        );
        assertEquals(List.of("Yes", "Never", "No", "yes"), rows.stream().map(ObservationRow::textValue).toList());
    }

    @Test
    void theRolledUpSegmentIsNotReportedAsADiscardedTextValue() throws IOException {
        Path file = tempDir.resolve("rollup.csv");
        Files.writeString(file, "1,\\a\\Yes\\,1,Yes,\n2,\\a\\,1,other,\n");
        Path failures = tempDir.resolve("rollup.jsonl");
        FileResult result;
        List<ObservationRow> rows = new ArrayList<>();
        try (FailureSink sink = new FailureSink(failures)) {
            result = new CsvObservationProducer("run", sink).processFile(file, rows::addAll, 10);
        }

        assertEquals(2, rows.size());
        assertEquals(
            Map.of(FailureReason.BOTH_VALUES_TEXT_DISCARDED, 1L), result.warnings(), "only the row whose text was really discarded"
        );
        assertEquals("\\a\\", rows.get(0).conceptPath());
        assertEquals(1.0, rows.get(0).numericValue());
    }

    @Test
    void numericLookingTextBecomesANumberWhenNvalIsEmpty() throws IOException {
        List<ObservationRow> rows = rows(
            "1,\\a\\,,3,", "2,\\a\\,,\" 4.5 \",", "3,\\a\\,,1e2,", "4,\\a\\,,12abc,", "5,\\a\\,,-7,", "6,\\a\\,,NaN,", "7,\\a\\,,Infinity,"
        );

        assertEquals(3.0, rows.get(0).numericValue());
        assertNull(rows.get(0).textValue());
        assertEquals(4.5, rows.get(1).numericValue());
        assertEquals(100.0, rows.get(2).numericValue());
        assertEquals("12abc", rows.get(3).textValue());
        assertEquals(-7.0, rows.get(4).numericValue());
        assertEquals("NaN", rows.get(5).textValue(), "not finite: stays text (and the writer treats it as a null sentinel)");
        assertEquals("Infinity", rows.get(6).textValue());
    }

    @Test
    void anEmptyNvalWithNumericTextAndANumericNvalGiveTheSameValue() throws IOException {
        List<ObservationRow> rows = rows("1,\\a\\,5,,", "2,\\a\\,,5,");

        assertEquals(rows.get(0).numericValue(), rows.get(1).numericValue());
    }

    @Test
    void textValuesAreTrimmedAndLoseReplacementCharacters() throws IOException {
        List<ObservationRow> rows = rows("1,\\a\\,,\"  hello� world \",", "2,\\a\\,,\"�\",");

        assertEquals("hello world", rows.get(0).textValue());
        assertEquals(1, rows.size(), "text that is only U+FFFD is empty: a row without a value");
    }

    @Test
    void nonFiniteNvalIsNotStoredAsANumber() throws IOException {
        List<ObservationRow> rows = rows("1,\\a\\,NaN,,", "2,\\a\\,Infinity,fallback,", "3,\\a\\,-Infinity,,");

        assertEquals(1, rows.size());
        assertEquals("fallback", rows.get(0).textValue());
    }

    @Test
    void malformedUtf8IsReplacedLikeCsvLoaderInsteadOfFailingTheFile() throws IOException {
        Path file = tempDir.resolve("latin1.csv");
        byte[] row = "1,\\a\\,,caf".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[row.length + 4];
        System.arraycopy(row, 0, bytes, 0, row.length);
        bytes[row.length] = (byte) 0xE9; // latin-1 é, invalid UTF-8
        bytes[row.length + 1] = ',';
        bytes[row.length + 2] = '\n';
        Files.write(file, java.util.Arrays.copyOf(bytes, row.length + 3));
        List<ObservationRow> rows = new ArrayList<>();
        try (FailureSink sink = new FailureSink(tempDir.resolve("f.jsonl"))) {
            new CsvObservationProducer("run", sink).processFile(file, rows::addAll, 10);
        }

        assertEquals("caf", rows.get(0).textValue(), "the replacement character is stripped, as CSVLoader does");
    }

    // ---- header rule (decision 6) -----------------------------------------------------------------------------------------

    @Test
    void headerlessInputIsDataFromTheFirstRow() throws IOException {
        assertEquals(2, rows("1,\\a\\,1,,", "2,\\a\\,2,,").size());
    }

    @Test
    void knownHeadersAreSkippedCaseInsensitivelyAndAfterABom() throws IOException {
        for (
            String header : List.of(
                "PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,DATE_TIME", "patient_num,concept_path,nval_num,tval_char,date_time",
                "﻿PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,DATE_TIME",
                "subject_id,concept_path,continuous_nval,categorical_tval,timestamp_ts"
            )
        ) {
            assertEquals(1, rows(header, "1,\\a\\,1,,").size(), header);
        }
    }

    @Test
    void aFirstRowThatIsNotAKnownHeaderIsDataAndFailsLoudly() throws IOException {
        Path file = tempDir.resolve("odd.csv");
        Files.writeString(file, "who,what,x,y,z\n1,\\a\\,1,,\n");
        FileResult result;
        List<ObservationRow> rows = new ArrayList<>();
        try (FailureSink sink = new FailureSink(tempDir.resolve("odd.jsonl"))) {
            result = new CsvObservationProducer("run", sink).processFile(file, rows::addAll, 10);
        }

        assertEquals(1, rows.size());
        assertEquals(Map.of(FailureReason.INVALID_PATIENT_ID, 1L), result.rejected());
    }

    // ---- failure bookkeeping ----------------------------------------------------------------------------------------------

    @Test
    void recordsPastTheCapAreStillCountedInTheFileResult() throws IOException {
        StringBuilder csv = new StringBuilder();
        int bad = CsvObservationProducer.MAX_WARNINGS_PER_REASON_PER_FILE + 250;
        for (int i = 1; i <= bad; i++) {
            csv.append(i).append(",\\a\\,1,,not-a-date\n");
        }
        Path file = tempDir.resolve("many-bad-timestamps.csv");
        Files.writeString(file, csv.toString());
        Path failures = tempDir.resolve("many.jsonl");
        FileResult result;
        try (FailureSink sink = new FailureSink(failures)) {
            result = new CsvObservationProducer("run", sink).processFile(file, rows -> {
            }, 100);
            assertEquals(CsvObservationProducer.MAX_WARNINGS_PER_REASON_PER_FILE + 1, sink.getTotalFailures());
            assertEquals(
                Map.of(FailureReason.TIMESTAMP_PARSE_ERROR, (long) CsvObservationProducer.MAX_WARNINGS_PER_REASON_PER_FILE + 1),
                sink.getRollup(), "the sink's rollup only knows what was written"
            );
        }

        assertEquals(Map.of(FailureReason.TIMESTAMP_PARSE_ERROR, (long) bad), result.warnings(), "the producer's counters are complete");
        assertEquals(bad, result.observationsProduced());
        List<String> lines = Files.readAllLines(failures);
        JsonNode summary = new ObjectMapper().readTree(lines.get(lines.size() - 1));
        assertTrue(summary.get("reasonDetail").asText().contains("Summary: " + bad));
        assertEquals("not-a-date", new ObjectMapper().readTree(lines.get(0)).get("timestampRaw").asText());
    }

    @Test
    void parallelFailuresCarryTheChunkSoTheLineCanBeFound() throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 400; i++) {
            csv.append(i).append(",\\a\\,").append(i == 250 ? "" : "1").append(",,\n"); // row 250 has no value
        }
        Path file = tempDir.resolve("parallel.csv");
        Files.writeString(file, csv.toString());
        Path failures = tempDir.resolve("parallel.jsonl");
        try (FailureSink sink = new FailureSink(failures)) {
            new CsvObservationProducer("run", sink, 1, 100).processFile(file, rows -> {
            }, 50);
        }

        List<String> lines = Files.readAllLines(failures);
        assertEquals(1, lines.size());
        JsonNode failure = new ObjectMapper().readTree(lines.get(0));
        assertEquals("MISSING_VALUE", failure.get("reasonCode").asText());
        assertEquals(250, failure.get("patientNum").asInt());
        assertTrue(failure.get("chunkIndex").asInt() > 0, "a record number alone is relative to the chunk: " + failure);
        long chunkStart = failure.get("chunkStartOffset").asLong();
        long recordInChunk = failure.get("recordNumber").asLong();
        // chunk start offset + record number locate the line: read the file from the offset and count records
        byte[] all = Files.readAllBytes(file);
        String fromChunk = new String(all, (int) chunkStart, all.length - (int) chunkStart, StandardCharsets.UTF_8);
        assertTrue(fromChunk.split("\n")[(int) recordInChunk - 1].startsWith("250,"), fromChunk.split("\n")[(int) recordInChunk - 1]);
    }

    @Test
    void sequentialFailuresHaveNoChunk() throws IOException {
        Path file = tempDir.resolve("seq.csv");
        Files.writeString(file, "1,\\a\\,1,,\n2,\\a\\,,,\n");
        Path failures = tempDir.resolve("seq.jsonl");
        try (FailureSink sink = new FailureSink(failures)) {
            new CsvObservationProducer("run", sink).processFile(file, rows -> {
            }, 50);
        }

        JsonNode failure = new ObjectMapper().readTree(Files.readAllLines(failures).get(0));
        assertEquals(2, failure.get("recordNumber").asInt());
        assertFalse(failure.has("chunkIndex"));
    }

    @Test
    void aBomAndAHeaderAreSkippedInTheParallelPathToo() throws IOException {
        StringBuilder csv = new StringBuilder("﻿PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,DATE_TIME\n");
        for (int i = 1; i <= 300; i++) {
            csv.append(i).append(",\\a\\,").append(i).append(",,\n");
        }
        Path file = tempDir.resolve("bom-parallel.csv");
        Files.writeString(file, csv.toString());
        List<ObservationRow> rows = Collections.synchronizedList(new ArrayList<>());
        FileResult result;
        try (FailureSink sink = new FailureSink(tempDir.resolve("bom-parallel.jsonl"))) {
            result = new CsvObservationProducer("run", sink, 1, 100).processFile(file, rows::addAll, 50);
            assertEquals(0, sink.getTotalFailures());
        }

        assertEquals(300, rows.size());
        assertEquals(300, result.recordsRead());
    }

    @Test
    void numbersCarryTheTextTheyWereReadFrom() throws IOException {
        List<ObservationRow> rows = rows("1,\\a\\,,007,", "2,\\a\\,1.50,,", "3,\\a\\,5,,", "4,\\a\\,2.5,,");

        assertEquals(List.of("007", "1.50", "5", "2.5"), rows.stream().map(ObservationRow::numericText).toList());
        assertEquals(List.of(7.0, 1.5, 5.0, 2.5), rows.stream().map(ObservationRow::numericValue).toList());
    }

    @Test
    void chunkConcurrencyIsBounded() throws IOException {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            csv.append(i).append(",\\a\\,").append(i).append(",,\n");
        }
        Path file = tempDir.resolve("bounded.csv");
        Files.writeString(file, csv.toString());
        for (int limit : new int[] {1, 2}) {
            java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger highest = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger rows = new java.util.concurrent.atomic.AtomicInteger();
            try (FailureSink sink = new FailureSink(tempDir.resolve("bounded-" + limit + ".jsonl"))) {
                new CsvObservationProducer("run", sink, 1, 100, limit).processFile(file, batch -> {
                    highest.accumulateAndGet(running.incrementAndGet(), Math::max);
                    rows.addAndGet(batch.size());
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                }, 10);
            }
            assertEquals(2000, rows.get());
            assertTrue(highest.get() <= limit, "at most " + limit + " chunks at once, saw " + highest.get());
        }
        assertEquals(1, CsvChunkProcessor.defaultConcurrency(1));
        assertEquals(1, CsvChunkProcessor.defaultConcurrency(2));
        assertTrue(CsvChunkProcessor.defaultConcurrency(16) <= 8);
        assertThrows(IllegalArgumentException.class, () -> new CsvChunkProcessor("run", null, 10, 0));
    }
}
