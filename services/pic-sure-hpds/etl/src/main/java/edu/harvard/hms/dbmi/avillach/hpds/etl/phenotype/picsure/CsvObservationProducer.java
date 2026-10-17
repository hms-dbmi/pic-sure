package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.ObservationRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Produces observations from allConcepts CSV files (the legacy split format).
 *
 * <p>Header detection is structural, not name-based: a first line with at least 4 columns whose first column is not an integer is treated
 * as a header. This accepts any header naming convention (e.g. PATIENT_NUM,... or subject_id,concept_path,continuous_nval,
 * categorical_tval,timestamp_ts) while a header-less file, whose first line starts with an integer patient num, stays in positional mode.
 *
 * <p>Columns (by position, header names are informational only): <ul> <li>0: PATIENT_NUM (integer)</li> <li>1: CONCEPT_PATH
 * (backslash-delimited string; a trailing backslash is added if missing)</li> <li>2: NVAL_NUM (numeric value, may be empty)</li> <li>3:
 * TVAL_CHAR (text value, may be empty)</li> <li>4: TIMESTAMP (optional, see {@link TimestampParser}: epoch milliseconds, ISO-8601, or
 * zoneless/date-only as UTC; blank or {@code 0} means none)</li> </ul>
 *
 * <p>Value rule (one explicit rule; nothing is dropped without a failure record): <ul> <li>only NVAL_NUM or only TVAL_CHAR: that
 * value;</li> <li>both present: <b>the numeric value wins</b> and the text value is discarded, with a
 * {@link FailureReason#BOTH_VALUES_TEXT_DISCARDED} failure record (the first {@value #MAX_BOTH_VALUES_RECORDS_PER_FILE} per file, then one
 * summary record with the remaining count). This matches the legacy {@code CSVLoader}, which also ignores the text of a row that has a
 * number;</li> <li>NVAL_NUM unparseable and TVAL_CHAR present: the text value is kept, with a {@link FailureReason#NUMERIC_PARSE_ERROR}
 * failure record;</li> <li>NVAL_NUM unparseable and no text, or neither value: the row is rejected with a failure record.</li> </ul>
 *
 * <p>Files larger than {@value #DEFAULT_LARGE_FILE_THRESHOLD_BYTES} bytes are split into row-aligned chunks and parsed in parallel; the
 * consumer must then be thread-safe.
 */
public class CsvObservationProducer {
    private static final Logger log = LoggerFactory.getLogger(CsvObservationProducer.class);

    /** Files above this size get parallel chunk processing. */
    static final long DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 500_000_000L;

    /** Individually recorded both-values rows per file; the rest are counted in one summary record. */
    static final int MAX_BOTH_VALUES_RECORDS_PER_FILE = 1000;

    private static final String DATASET_ID = "csv";

    // Canonical column names used for named access after header detection (assigned positionally)
    private static final String HEADER_PATIENT_NUM = "PATIENT_NUM";
    private static final String HEADER_CONCEPT_PATH = "CONCEPT_PATH";
    private static final String HEADER_NVAL_NUM = "NVAL_NUM";
    private static final String HEADER_TVAL_CHAR = "TVAL_CHAR";
    private static final String HEADER_TIMESTAMP = "TIMESTAMP";

    private final String runId;
    private final FailureSink failureSink;
    private final CsvChunkProcessor chunkProcessor;
    private final long largeFileThresholdBytes;

    // Per-file tallies (parseRecord runs concurrently from chunk threads)
    private final ConcurrentHashMap<Path, FileStats> fileStats = new ConcurrentHashMap<>();

    private static class FileStats {
        final AtomicLong timestampFailures = new AtomicLong();
        volatile String firstTimestampSample;
        final AtomicLong bothValues = new AtomicLong();
    }

    private record ParseCounts(long recordsRead, long observations) {
    }

    public CsvObservationProducer(String runId, FailureSink failureSink) {
        this(runId, failureSink, DEFAULT_LARGE_FILE_THRESHOLD_BYTES, CsvChunkProcessor.DEFAULT_CHUNK_SIZE_ROWS);
    }

    /** Package-private so tests can exercise the parallel path with small files. */
    CsvObservationProducer(String runId, FailureSink failureSink, long largeFileThresholdBytes, long chunkSizeRows) {
        this.runId = runId;
        this.failureSink = failureSink;
        this.largeFileThresholdBytes = largeFileThresholdBytes;
        this.chunkProcessor = new CsvChunkProcessor(runId, failureSink, chunkSizeRows);
    }

    /**
     * Processes a single CSV file with a streaming parser. Automatically detects whether the file has a header, and uses parallel chunk
     * processing for large files.
     *
     * @param filePath path to CSV file
     * @param consumer callback for each batch (called from several threads for large files)
     * @param batchSize rows per batch
     */
    public void processFile(Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize) throws IOException {
        long fileSize = Files.size(filePath);
        long startTime = System.currentTimeMillis();

        MDC.put("file_name", filePath.getFileName().toString());
        MDC.put("dataset_id", DATASET_ID);
        try {
            log.atInfo().addKeyValue("event_type", "file.processing.started").addKeyValue("event_schema_version", "1.0")
                .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                .log("Processing CSV file: {} ({} MB)", filePath.getFileName(), fileSize / 1024 / 1024);

            if (fileSize > largeFileThresholdBytes) {
                log.info("Large CSV detected ({} MB), using parallel chunk processing", fileSize / 1024 / 1024);
                chunkProcessor.processLargeFileInParallel(filePath, consumer, batchSize, this);

                long elapsedMs = System.currentTimeMillis() - startTime;
                log.atInfo().addKeyValue("event_type", "file.processing.completed").addKeyValue("event_schema_version", "1.0")
                    .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                    .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                    .addKeyValue("processing_mode", "parallel").addKeyValue("elapsed_ms", elapsedMs)
                    .log("Completed processing CSV file: {} (parallel mode, {} ms)", filePath.getFileName(), elapsedMs);
                return;
            }

            log.debug("Processing CSV file sequentially: {} ({} MB)", filePath, fileSize / 1024 / 1024);
            processFileSequential(filePath, consumer, batchSize, startTime, fileSize);
        } finally {
            // Summaries cover sequential, parallel and failed runs alike (remove() inside keeps them to one per file)
            logFileSummary(filePath);
            MDC.remove("file_name");
            MDC.remove("dataset_id");
        }
    }

    /**
     * Processes a file with the sequential parser regardless of its size. This is also the fallback the chunk processor uses when a large
     * file turns out to be a single chunk; it must never go back through {@link #processFile}, which would pick the parallel path again.
     */
    void processFileSequentially(Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize) throws IOException {
        processFileSequential(filePath, consumer, batchSize, System.currentTimeMillis(), Files.size(filePath));
    }

    private void processFileSequential(Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize, long startTime, long fileSize)
        throws IOException {

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                log.warn("CSV file is empty: {}", filePath);
                log.atWarn().addKeyValue("event_type", "file.processing.completed").addKeyValue("event_schema_version", "1.0")
                    .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                    .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                    .addKeyValue("records_read", 0L).addKeyValue("observations_generated", 0L)
                    .addKeyValue("elapsed_ms", System.currentTimeMillis() - startTime).addKeyValue("warning", "empty_file")
                    .log("Completed processing empty CSV file: {}", filePath.getFileName());
                return;
            }

            boolean useHeaders = isHeaderLine(firstLine);
            if (useHeaders) {
                log.info("Detected header row in {}, using named column access", filePath.getFileName());
                // The header line is already consumed from this reader, so parsing continues at the first data row
                CSVFormat format =
                    dataFormat().withHeader(HEADER_PATIENT_NUM, HEADER_CONCEPT_PATH, HEADER_NVAL_NUM, HEADER_TVAL_CHAR, HEADER_TIMESTAMP)
                        .withSkipHeaderRecord(false);
                processWithFormat(reader, filePath, format, true, consumer, batchSize, startTime, fileSize);
            } else {
                log.info("No valid header detected in {}, using positional column access", filePath.getFileName());
                // The first line is data: start over with a fresh reader
                CSVFormat format = dataFormat();
                try (BufferedReader dataReader = Files.newBufferedReader(filePath)) {
                    processWithFormat(dataReader, filePath, format, false, consumer, batchSize, startTime, fileSize);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.atError().addKeyValue("event_type", "file.processing.failed").addKeyValue("event_schema_version", "1.0")
                .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                .addKeyValue("elapsed_ms", System.currentTimeMillis() - startTime).addKeyValue("error_message", e.getMessage())
                .log("Failed to process CSV file: {}", filePath.getFileName());
            throw e;
        }
    }

    private void processWithFormat(
        BufferedReader reader, Path filePath, CSVFormat format, boolean useHeaders, Consumer<List<ObservationRow>> consumer, int batchSize,
        long startTime, long fileSize
    ) throws IOException {
        try (CSVParser parser = new CSVParser(reader, format)) {
            log.debug("CSV processing thread: {} (file: {})", Thread.currentThread().getName(), filePath.getFileName());
            ParseCounts counts = parseRecords(parser, filePath, useHeaders, consumer, batchSize);

            long elapsedMs = System.currentTimeMillis() - startTime;
            log.atInfo().addKeyValue("event_type", "file.processing.completed").addKeyValue("event_schema_version", "1.0")
                .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                .addKeyValue("records_read", counts.recordsRead()).addKeyValue("observations_generated", counts.observations())
                .addKeyValue("elapsed_ms", elapsedMs).addKeyValue("processing_mode", "sequential").log(
                    "Completed processing CSV file: {} ({} records, {} observations, {} ms)", filePath.getFileName(), counts.recordsRead(),
                    counts.observations(), elapsedMs
                );
        }
    }

    /** The monorepo pins commons-csv 1.5, which predates CSVFormat.builder(). */
    private static CSVFormat dataFormat() {
        return CSVFormat.DEFAULT.withIgnoreEmptyLines(true).withTrim().withQuote('"');
    }

    /**
     * Processes a chunk of a large file (used by {@link CsvChunkProcessor}). The reader is positioned at a row boundary and contains no
     * header.
     *
     * @return the number of observations produced
     */
    long processStream(BufferedReader reader, Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize, boolean hasHeaders)
        throws IOException {
        try (CSVParser parser = new CSVParser(reader, dataFormat())) {
            return parseRecords(parser, filePath, hasHeaders, consumer, batchSize).observations();
        }
    }

    /**
     * The single parse loop for the sequential and the parallel path, so both record failures the same way. An exception from the consumer
     * (the writer) is not a parse error and propagates.
     */
    private ParseCounts parseRecords(
        CSVParser parser, Path filePath, boolean useHeaders, Consumer<List<ObservationRow>> consumer, int batchSize
    ) {
        List<ObservationRow> batch = new ArrayList<>(batchSize);
        long recordsRead = 0;
        long observations = 0;

        for (CSVRecord record : parser) {
            recordsRead++;
            ObservationRow row;
            try {
                row = parseRecord(record, filePath, useHeaders);
            } catch (RuntimeException e) {
                log.warn("Failed to parse CSV record {} of {}: {}", record.getRecordNumber(), filePath.getFileName(), e.getMessage());
                recordFailure(filePath, record.getRecordNumber(), null, null, null, null, FailureReason.UNKNOWN, e.toString());
                continue;
            }
            if (row == null) {
                continue;
            }
            batch.add(row);
            observations++;
            if (batch.size() >= batchSize) {
                consumer.accept(batch);
                batch = new ArrayList<>(batchSize);
            }
        }
        if (!batch.isEmpty()) {
            consumer.accept(batch);
        }
        return new ParseCounts(recordsRead, observations);
    }

    /**
     * Detects if a CSV line is a header row by parsing it first. Package-private so CsvChunkProcessor shares the same gate.
     */
    static boolean isHeaderLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        try (CSVParser parser = CSVParser.parse(line, CSVFormat.DEFAULT.withQuote('"'))) {
            return isHeaderRecord(parser.iterator().next());
        } catch (Exception e) {
            log.debug("Failed to parse first line as CSV, assuming positional: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Structural header gate: a record is a header if it has at least 4 columns and its first column is not an integer. Data rows always
     * start with an integer patient num, so any header naming convention is accepted without an alias list.
     */
    static boolean isHeaderRecord(CSVRecord record) {
        if (record.size() < 4) {
            return false;
        }
        try {
            Integer.parseInt(record.get(0).trim());
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Parses a CSV record into an ObservationRow; package-private as the seam for tests of the failure handling around it.
     *
     * @param useHeaders if true, use named column access; if false, use positional
     * @return the row, or null if the record was rejected (and a failure recorded)
     */
    ObservationRow parseRecord(CSVRecord record, Path filePath, boolean useHeaders) {
        long recordNumber = record.getRecordNumber();

        if (!useHeaders && record.size() < 4) {
            recordFailure(
                filePath, recordNumber, null, null, null, null, FailureReason.UNKNOWN,
                "Record has only " + record.size() + " columns, expected at least 4"
            );
            return null;
        }

        String patientNumRaw;
        try {
            patientNumRaw = useHeaders ? record.get(HEADER_PATIENT_NUM) : record.get(0);
        } catch (IllegalArgumentException e) {
            recordFailure(
                filePath, recordNumber, null, null, null, null, FailureReason.MISSING_PATIENT_ID,
                "PATIENT_NUM column not found: " + e.getMessage()
            );
            return null;
        }
        if (patientNumRaw == null || patientNumRaw.isBlank()) {
            recordFailure(filePath, recordNumber, null, null, null, null, FailureReason.MISSING_PATIENT_ID, "PATIENT_NUM is null or empty");
            return null;
        }

        int patientNum;
        try {
            patientNum = Integer.parseInt(patientNumRaw.trim());
        } catch (NumberFormatException e) {
            recordFailure(
                filePath, recordNumber, patientNumRaw, null, null, null, FailureReason.INVALID_PATIENT_ID,
                "Cannot parse as integer: " + patientNumRaw
            );
            return null;
        }

        String conceptPath;
        try {
            conceptPath = useHeaders ? record.get(HEADER_CONCEPT_PATH) : record.get(1);
        } catch (IllegalArgumentException e) {
            recordFailure(
                filePath, recordNumber, patientNumRaw, patientNum, null, null, FailureReason.MISSING_CONCEPT_PATH,
                "CONCEPT_PATH column not found: " + e.getMessage()
            );
            return null;
        }
        if (conceptPath == null || conceptPath.isBlank()) {
            recordFailure(
                filePath, recordNumber, patientNumRaw, patientNum, null, null, FailureReason.MISSING_CONCEPT_PATH,
                "CONCEPT_PATH is null or empty"
            );
            return null;
        }
        if (!conceptPath.endsWith("\\")) {
            conceptPath = conceptPath + "\\";
        }

        String nvalRaw;
        String tvalRaw;
        try {
            nvalRaw = useHeaders ? record.get(HEADER_NVAL_NUM) : record.get(2);
            tvalRaw = useHeaders ? record.get(HEADER_TVAL_CHAR) : record.get(3);
        } catch (IllegalArgumentException e) {
            recordFailure(
                filePath, recordNumber, patientNumRaw, patientNum, conceptPath, null, FailureReason.UNKNOWN,
                "Value column not found: " + e.getMessage()
            );
            return null;
        }

        Double numericValue = null;
        String textValue = null;
        boolean hasNumeric = nvalRaw != null && !nvalRaw.isBlank();
        boolean hasText = tvalRaw != null && !tvalRaw.isBlank();

        if (hasNumeric) {
            try {
                numericValue = Double.parseDouble(nvalRaw);
            } catch (NumberFormatException e) {
                if (!hasText) {
                    recordFailure(
                        filePath, recordNumber, patientNumRaw, patientNum, conceptPath, nvalRaw, FailureReason.NUMERIC_PARSE_ERROR,
                        "Cannot parse NVAL_NUM: " + nvalRaw
                    );
                    return null;
                }
                recordFailure(
                    filePath, recordNumber, patientNumRaw, patientNum, conceptPath, nvalRaw, FailureReason.NUMERIC_PARSE_ERROR,
                    "Cannot parse NVAL_NUM: " + nvalRaw + "; kept the text value"
                );
            }
        }
        if (hasText) {
            textValue = tvalRaw;
        }

        if (numericValue == null && textValue == null) {
            recordFailure(
                filePath, recordNumber, patientNumRaw, patientNum, conceptPath, null, FailureReason.MISSING_VALUE,
                "Both NVAL_NUM and TVAL_CHAR are null"
            );
            return null;
        }

        if (numericValue != null && textValue != null) {
            // Value rule: the numeric value wins (as in the legacy CSVLoader). The writer takes exactly one value per observation.
            long seen = fileStats(filePath).bothValues.incrementAndGet();
            if (seen <= MAX_BOTH_VALUES_RECORDS_PER_FILE) {
                recordFailure(
                    filePath, recordNumber, patientNumRaw, patientNum, conceptPath, tvalRaw, FailureReason.BOTH_VALUES_TEXT_DISCARDED,
                    "Kept NVAL_NUM " + nvalRaw + ", discarded TVAL_CHAR"
                );
            }
            textValue = null;
        }

        Instant timestamp = null;
        String timestampRaw;
        if (useHeaders) {
            try {
                timestampRaw = record.get(HEADER_TIMESTAMP);
            } catch (IllegalArgumentException e) {
                timestampRaw = null; // column not present
            }
        } else {
            timestampRaw = record.size() > 4 ? record.get(4) : null;
        }
        try {
            timestamp = TimestampParser.parse(timestampRaw);
        } catch (DateTimeParseException e) {
            // Non-fatal: the row is ingested without a timestamp. WARN on the first failure per file, count the rest, summarize at the end.
            recordTimestampFailure(filePath, timestampRaw, patientNum);
        }

        return new ObservationRow(patientNum, conceptPath, numericValue, textValue, timestamp);
    }

    private FileStats fileStats(Path filePath) {
        return fileStats.computeIfAbsent(filePath, p -> new FileStats());
    }

    private void recordTimestampFailure(Path filePath, String timestampRaw, int patientNum) {
        FileStats stats = fileStats(filePath);
        if (stats.timestampFailures.getAndIncrement() == 0) {
            stats.firstTimestampSample = timestampRaw;
            log.warn(
                "Cannot parse timestamp '{}' for patient {} in {}, continuing without timestamp (further failures counted, summary at file completion)",
                timestampRaw, patientNum, filePath.getFileName()
            );
        } else {
            log.debug("Cannot parse timestamp '{}' for patient {}, continuing without timestamp", timestampRaw, patientNum);
        }
    }

    /**
     * Logs the per-file tallies, and records the both-values rows beyond the individually recorded ones as one summary failure record.
     */
    private void logFileSummary(Path filePath) {
        FileStats stats = fileStats.remove(filePath);
        if (stats == null) {
            return;
        }
        if (stats.timestampFailures.get() > 0) {
            log.warn(
                "File {}: {} timestamp values could not be parsed and were ingested as null (first sample: '{}')", filePath.getFileName(),
                stats.timestampFailures.get(), stats.firstTimestampSample
            );
        }
        long bothValues = stats.bothValues.get();
        if (bothValues > 0) {
            log.warn(
                "File {}: {} rows had both a numeric and a text value; the numeric value was kept and the text value discarded",
                filePath.getFileName(), bothValues
            );
            if (bothValues > MAX_BOTH_VALUES_RECORDS_PER_FILE) {
                recordFailure(
                    filePath, null, null, null, null, null, FailureReason.BOTH_VALUES_TEXT_DISCARDED,
                    "Summary: " + bothValues
                        + " rows in this file had both NVAL_NUM and TVAL_CHAR; the numeric value was kept. Only the first "
                        + MAX_BOTH_VALUES_RECORDS_PER_FILE + " are recorded individually; "
                        + (bothValues - MAX_BOTH_VALUES_RECORDS_PER_FILE) + " more were not."
                );
            }
        }
    }

    private void recordFailure(
        Path file, Long recordNumber, String participantId, Integer patientNum, String conceptPath, String valueRaw, FailureReason reason,
        String detail
    ) {
        failureSink.recordFailure(
            new FailureRecord(
                runId, file.getFileName().toString(), file.toString(), recordNumber, participantId, patientNum, conceptPath, null, valueRaw,
                reason, detail
            )
        );
    }
}
