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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * Produces observations from allConcepts CSV files (the legacy split format) with the semantics of the legacy {@code CSVLoader}.
 *
 * <p><b>Columns</b> (by position): <ul> <li>0: PATIENT_NUM (integer)</li> <li>1: CONCEPT_PATH</li> <li>2: NVAL_NUM (numeric value, may be
 * empty)</li> <li>3: TVAL_CHAR (text value, may be empty)</li> <li>4: TIMESTAMP (optional, see {@link TimestampParser}: epoch milliseconds,
 * ISO-8601, or zoneless/date-only as UTC; blank or {@code 0} means none)</li> </ul>
 *
 * <p><b>Header.</b> The splitter output has no header. The first non-blank record is a header only when its first two cells are known
 * header names (for example {@code PATIENT_NUM,CONCEPT_PATH,...} or {@code subject_id,concept_path,...}, case-insensitive, after stripping
 * a UTF-8 byte order mark); anything else is data, so a first row that is malformed is reported instead of swallowed.
 *
 * <p><b>Concept path</b> (as {@code CSVLoader} with {@code CSVParserUtil.parseConceptPath(record, false, null)}): {@code µ} becomes
 * {@code \}, every segment is trimmed, U+FFFD is removed, and the path ends in {@code \}. A path that is blank is rejected (the legacy
 * loader would make it the root {@code \}). When the text value equals the last path segment, that segment is dropped
 * ({@code CSVLoader.java:121-123}).
 *
 * <p><b>Value rule</b> (nothing is dropped without a failure record): <ul> <li>NVAL_NUM present and a finite number: numeric; a text value
 * is discarded with a {@link FailureReason#BOTH_VALUES_TEXT_DISCARDED} warning (not when the text is the path segment that was dropped);
 * this is the legacy loader's rule;</li> <li>NVAL_NUM empty: the text value (trimmed, U+FFFD removed) is numeric when
 * {@code Double.parseDouble} accepts it as a finite number (the legacy loader's rule), otherwise categorical;</li> <li>NVAL_NUM present but
 * not a finite number: the text value is used when there is one, with a {@link FailureReason#NUMERIC_PARSE_ERROR} warning; without a text
 * value the row is rejected;</li> <li>neither value: rejected with {@link FailureReason#MISSING_VALUE}.</li> </ul> Null sentinels ("NA",
 * "None", ...) are not decided here: the writer treats them (see {@code SpoolingLoadingStore}).
 *
 * <p>Failure records of a warning kind are written individually for the first {@value #MAX_WARNINGS_PER_REASON_PER_FILE} per reason and
 * file, then one summary record with the total. The counts in {@link FileResult} are always complete.
 *
 * <p>Files larger than {@value #DEFAULT_LARGE_FILE_THRESHOLD_BYTES} bytes are split into row-aligned chunks and parsed in parallel; the
 * consumer must then be thread-safe.
 */
public class CsvObservationProducer {
    private static final Logger log = LoggerFactory.getLogger(CsvObservationProducer.class);

    /** Files above this size get parallel chunk processing. */
    static final long DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 500_000_000L;

    /** Individually recorded warning rows per reason and file; the rest are counted in one summary record. */
    static final int MAX_WARNINGS_PER_REASON_PER_FILE = 1000;
    /** Kept under the old name for the both-values rule. */
    static final int MAX_BOTH_VALUES_RECORDS_PER_FILE = MAX_WARNINGS_PER_REASON_PER_FILE;

    private static final String DATASET_ID = "csv";
    private static final char BOM = '﻿';
    private static final String MU = "µ";
    private static final String REPLACEMENT_CHARACTER = "�";

    private static final Set<String> PATIENT_HEADERS = Set.of("patient_num", "patient_id", "patientnum", "patient", "subject_id");
    private static final Set<String> CONCEPT_HEADERS = Set.of("concept_path", "conceptpath", "concept_cd", "concept");

    private final String runId;
    private final FailureSink failureSink;
    private final CsvChunkProcessor chunkProcessor;
    private final long largeFileThresholdBytes;

    // Per-file tallies (parseRecord runs concurrently from chunk threads)
    private final ConcurrentHashMap<Path, FileStats> fileStats = new ConcurrentHashMap<>();

    /** Where a record sits when its file was parsed in chunks; {@link #NONE} for a file parsed in one piece. */
    record RecordContext(Integer chunkIndex, Long chunkStartOffset) {
        static final RecordContext NONE = new RecordContext(null, null);
    }

    /**
     * What happened to the records of one file.
     *
     * @param recordsRead CSV records read (blank lines and a header are not records)
     * @param observationsProduced rows handed to the consumer
     * @param rejected records dropped, by reason; {@code recordsRead - sum(rejected) == observationsProduced}
     * @param warnings rows that were kept but noted, by reason; always complete even where the failure file holds fewer records
     */
    public record FileResult(
        long recordsRead, long observationsProduced, Map<FailureReason, Long> rejected, Map<FailureReason, Long> warnings
    ) {

        public long rejectedTotal() {
            return rejected.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private static class FileStats {
        final AtomicLong recordsRead = new AtomicLong();
        final AtomicLong observations = new AtomicLong();
        final AtomicLongArray rejected = new AtomicLongArray(FailureReason.values().length);
        final AtomicLongArray warned = new AtomicLongArray(FailureReason.values().length);
        volatile String firstTimestampSample;

        FileResult result() {
            Map<FailureReason, Long> rej = new EnumMap<>(FailureReason.class);
            Map<FailureReason, Long> warn = new EnumMap<>(FailureReason.class);
            for (FailureReason reason : FailureReason.values()) {
                if (rejected.get(reason.ordinal()) > 0) {
                    rej.put(reason, rejected.get(reason.ordinal()));
                }
                if (warned.get(reason.ordinal()) > 0) {
                    warn.put(reason, warned.get(reason.ordinal()));
                }
            }
            return new FileResult(recordsRead.get(), observations.get(), rej, warn);
        }
    }

    private record ParseCounts(long recordsRead, long observations) {
    }

    public CsvObservationProducer(String runId, FailureSink failureSink) {
        this(runId, failureSink, CsvChunkProcessor.defaultConcurrency(LoaderConfig.DEFAULT_STORE_CACHE_SIZE));
    }

    /** @param chunkConcurrency chunks of a large file parsed at once (see {@link LoaderConfig#effectiveChunkConcurrency()}) */
    public CsvObservationProducer(String runId, FailureSink failureSink, int chunkConcurrency) {
        this(runId, failureSink, DEFAULT_LARGE_FILE_THRESHOLD_BYTES, CsvChunkProcessor.DEFAULT_CHUNK_SIZE_ROWS, chunkConcurrency);
    }

    /** Package-private so tests can exercise the parallel path with small files. */
    CsvObservationProducer(String runId, FailureSink failureSink, long largeFileThresholdBytes, long chunkSizeRows) {
        this(
            runId, failureSink, largeFileThresholdBytes, chunkSizeRows,
            CsvChunkProcessor.defaultConcurrency(LoaderConfig.DEFAULT_STORE_CACHE_SIZE)
        );
    }

    CsvObservationProducer(String runId, FailureSink failureSink, long largeFileThresholdBytes, long chunkSizeRows, int chunkConcurrency) {
        this.runId = runId;
        this.failureSink = failureSink;
        this.largeFileThresholdBytes = largeFileThresholdBytes;
        this.chunkProcessor = new CsvChunkProcessor(runId, failureSink, chunkSizeRows, chunkConcurrency);
    }

    /**
     * Processes a single CSV file with a streaming parser, using parallel chunk processing for large files.
     *
     * @param filePath path to CSV file (a symbolic link is fine)
     * @param consumer callback for each batch (called from several threads for large files)
     * @param batchSize rows per batch
     * @return what happened to the records
     * @throws IOException if the file cannot be read or any chunk failed
     */
    public FileResult processFile(Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize) throws IOException {
        long fileSize = Files.size(filePath);
        long startTime = System.currentTimeMillis();
        boolean completed = false;

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
            } else {
                log.debug("Processing CSV file sequentially: {} ({} MB)", filePath, fileSize / 1024 / 1024);
                processFileSequential(filePath, consumer, batchSize, startTime, fileSize);
            }
            completed = true;
        } finally {
            MDC.remove("file_name");
            MDC.remove("dataset_id");
            if (!completed) {
                finishFile(filePath); // log the tallies and drop the bookkeeping of a failed file
            }
        }
        return finishFile(filePath);
    }

    /**
     * Processes a file with the sequential parser regardless of its size. This is also the fallback the chunk processor uses when a large
     * file turns out to be a single chunk; it must never go back through {@link #processFile}, which would pick the parallel path again.
     */
    void processFileSequentially(Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize) throws IOException {
        processFileSequential(filePath, consumer, batchSize, System.currentTimeMillis(), Files.size(filePath));
    }

    /** UTF-8 reader that replaces malformed bytes with U+FFFD like the legacy loader's FileReader, and skips a byte order mark. */
    static BufferedReader openReader(Path file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 1 << 16);
        try {
            reader.mark(1);
            int first = reader.read();
            if (first != BOM) {
                reader.reset();
            }
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
        return reader;
    }

    private void processFileSequential(Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize, long startTime, long fileSize)
        throws IOException {

        try {
            boolean hasHeader = false;
            boolean empty = true;
            try (BufferedReader probe = openReader(filePath)) {
                String line;
                while ((line = probe.readLine()) != null) {
                    if (!line.isBlank()) {
                        empty = false;
                        hasHeader = isHeaderLine(line);
                        break;
                    }
                }
            }
            if (empty) {
                log.warn("CSV file is empty: {}", filePath);
                log.atWarn().addKeyValue("event_type", "file.processing.completed").addKeyValue("event_schema_version", "1.0")
                    .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                    .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                    .addKeyValue("records_read", 0L).addKeyValue("observations_generated", 0L)
                    .addKeyValue("elapsed_ms", System.currentTimeMillis() - startTime).addKeyValue("warning", "empty_file")
                    .log("Completed processing empty CSV file: {}", filePath.getFileName());
                return;
            }

            try (BufferedReader reader = openReader(filePath)) {
                if (hasHeader) {
                    log.info("Detected header row in {}, skipping it", filePath.getFileName());
                    String line;
                    do {
                        line = reader.readLine(); // leading blank lines, then the header itself
                    } while (line != null && line.isBlank());
                } else {
                    log.info("No header in {}: the first record is data", filePath.getFileName());
                }
                try (CSVParser parser = new CSVParser(reader, dataFormat())) {
                    log.debug("CSV processing thread: {} (file: {})", Thread.currentThread().getName(), filePath.getFileName());
                    ParseCounts counts = parseRecords(parser, filePath, RecordContext.NONE, consumer, batchSize);

                    long elapsedMs = System.currentTimeMillis() - startTime;
                    log.atInfo().addKeyValue("event_type", "file.processing.completed").addKeyValue("event_schema_version", "1.0")
                        .addKeyValue("source_type", "csv").addKeyValue("file_path", filePath.toString())
                        .addKeyValue("file_name", filePath.getFileName().toString()).addKeyValue("file_size_bytes", fileSize)
                        .addKeyValue("records_read", counts.recordsRead()).addKeyValue("observations_generated", counts.observations())
                        .addKeyValue("elapsed_ms", elapsedMs).addKeyValue("processing_mode", "sequential").log(
                            "Completed processing CSV file: {} ({} records, {} observations, {} ms)", filePath.getFileName(),
                            counts.recordsRead(), counts.observations(), elapsedMs
                        );
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
    long processStream(BufferedReader reader, Path filePath, Consumer<List<ObservationRow>> consumer, int batchSize, RecordContext context)
        throws IOException {
        try (CSVParser parser = new CSVParser(reader, dataFormat())) {
            return parseRecords(parser, filePath, context, consumer, batchSize).observations();
        }
    }

    /**
     * The single parse loop for the sequential and the parallel path, so both record failures the same way. An exception from the consumer
     * (the writer) is not a parse error and propagates.
     */
    private ParseCounts parseRecords(
        CSVParser parser, Path filePath, RecordContext context, Consumer<List<ObservationRow>> consumer, int batchSize
    ) {
        FileStats stats = fileStats(filePath);
        List<ObservationRow> batch = new ArrayList<>(batchSize);
        long recordsRead = 0;
        long observations = 0;

        try {
            for (CSVRecord record : parser) {
                recordsRead++;
                ObservationRow row;
                try {
                    row = parseRecord(record, filePath, context);
                } catch (RuntimeException e) {
                    log.warn("Failed to parse CSV record {} of {}: {}", record.getRecordNumber(), filePath.getFileName(), e.getMessage());
                    reject(filePath, context, record.getRecordNumber(), null, null, null, null, FailureReason.UNKNOWN, e.toString());
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
        } finally {
            stats.recordsRead.addAndGet(recordsRead);
            stats.observations.addAndGet(observations);
        }
        return new ParseCounts(recordsRead, observations);
    }

    /** Detects if a CSV line is a header row by parsing it first. Package-private so CsvChunkProcessor shares the same gate. */
    static boolean isHeaderLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        try (CSVParser parser = CSVParser.parse(line, CSVFormat.DEFAULT.withQuote('"'))) {
            return isHeaderRecord(parser.iterator().next());
        } catch (Exception e) {
            log.debug("Failed to parse first line as CSV, assuming data: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Header gate: a record is a header if it has at least 4 columns and its first two cells are known header names (case-insensitive, byte
     * order mark stripped). Everything else is data.
     */
    static boolean isHeaderRecord(CSVRecord record) {
        if (record.size() < 4) {
            return false;
        }
        return PATIENT_HEADERS.contains(headerKey(record.get(0))) && CONCEPT_HEADERS.contains(headerKey(record.get(1)));
    }

    private static String headerKey(String cell) {
        String text = cell == null ? "" : cell;
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalises a concept path exactly as {@code CSVLoader} does through {@code CSVParserUtil.parseConceptPath(record, false, null)} and
     * its own second trim: µ becomes a backslash, segments are trimmed, U+FFFD is removed, and the path ends in a backslash.
     */
    static String normalizeConceptPath(String raw) {
        String path = joinTrimmed(raw.replace(MU, "\\")).replace(REPLACEMENT_CHARACTER, "");
        return joinTrimmed(path).replace(REPLACEMENT_CHARACTER, "");
    }

    /**
     * {@code String.join("\\", trimmed segments of split("\\\\")) + "\\"}: split drops trailing empty segments, so one backslash ends it.
     */
    private static String joinTrimmed(String path) {
        String[] segments = path.split("\\\\");
        StringBuilder out = new StringBuilder(path.length() + 1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('\\');
            }
            out.append(segments[i].trim());
        }
        return out.append('\\').toString();
    }

    /** Text of a cell as the legacy loader uses it: trimmed, U+FFFD removed. */
    private static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        return text.indexOf('�') >= 0 ? text.replace(REPLACEMENT_CHARACTER, "") : text;
    }

    /**
     * {@code Double.parseDouble} (the legacy loader's rule) restricted to finite numbers; null when the text is not one. Text that cannot
     * start a number is turned away before the parser so categorical columns do not pay for an exception per row.
     */
    static Double parseFinite(String text) {
        if (text.isEmpty()) {
            return null;
        }
        char first = text.charAt(0);
        if (!(first >= '0' && first <= '9') && first != '-' && first != '+' && first != '.') {
            return null; // "NaN" and "Infinity" are not finite anyway
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a CSV record into an ObservationRow; package-private as the seam for tests of the failure handling around it.
     *
     * @return the row, or null if the record was rejected (and a failure recorded)
     */
    ObservationRow parseRecord(CSVRecord record, Path filePath, RecordContext context) {
        long recordNumber = record.getRecordNumber();

        if (record.size() < 4) {
            reject(
                filePath, context, recordNumber, null, null, null, null, FailureReason.MALFORMED_RECORD,
                "Record has only " + record.size() + " columns, expected at least 4"
            );
            return null;
        }

        String patientNumRaw = record.get(0);
        if (patientNumRaw == null || patientNumRaw.isBlank()) {
            reject(
                filePath, context, recordNumber, null, null, null, null, FailureReason.MISSING_PATIENT_ID, "PATIENT_NUM is null or empty"
            );
            return null;
        }

        int patientNum;
        try {
            patientNum = Integer.parseInt(patientNumRaw.trim());
        } catch (NumberFormatException e) {
            reject(
                filePath, context, recordNumber, patientNumRaw, null, null, null, FailureReason.INVALID_PATIENT_ID,
                "Cannot parse as integer: " + patientNumRaw
            );
            return null;
        }

        String rawPath = record.get(1);
        if (rawPath == null || rawPath.isBlank()) {
            reject(
                filePath, context, recordNumber, patientNumRaw, patientNum, null, null, FailureReason.MISSING_CONCEPT_PATH,
                "CONCEPT_PATH is null or empty"
            );
            return null;
        }
        String conceptPath = normalizeConceptPath(rawPath);
        String text = cleanText(record.get(3));

        // The text value is the last path segment: the legacy loader drops that segment (CSVLoader.java:121-123)
        boolean segmentDropped = false;
        if (!text.isEmpty() && conceptPath.endsWith("\\" + text + "\\")) {
            conceptPath = conceptPath.substring(0, conceptPath.lastIndexOf('\\', conceptPath.length() - 2) + 1);
            segmentDropped = true;
        }

        String nvalRaw = record.get(2) == null ? "" : record.get(2).trim();
        Double numericValue = null;
        String textValue = null;
        String numericText = null; // the cell the number was read from, kept in case the concept ends up categorical

        if (!nvalRaw.isEmpty()) {
            numericValue = parseFinite(nvalRaw);
            numericText = numericValue == null ? null : nvalRaw;
            if (numericValue == null) {
                if (text.isEmpty()) {
                    reject(
                        filePath, context, recordNumber, patientNumRaw, patientNum, conceptPath, nvalRaw, FailureReason.NUMERIC_PARSE_ERROR,
                        "Cannot parse NVAL_NUM as a finite number: " + nvalRaw
                    );
                    return null;
                }
                warn(
                    filePath, context, recordNumber, patientNumRaw, patientNum, conceptPath, nvalRaw, FailureReason.NUMERIC_PARSE_ERROR,
                    "Cannot parse NVAL_NUM as a finite number: " + nvalRaw + "; used the text value"
                );
            } else if (!text.isEmpty() && !segmentDropped) {
                // Value rule: the numeric value wins (as in the legacy CSVLoader). The writer takes exactly one value per observation.
                warn(
                    filePath, context, recordNumber, patientNumRaw, patientNum, conceptPath, text, FailureReason.BOTH_VALUES_TEXT_DISCARDED,
                    "Kept NVAL_NUM " + nvalRaw + ", discarded TVAL_CHAR"
                );
            }
        }
        if (numericValue == null) {
            if (text.isEmpty()) {
                reject(
                    filePath, context, recordNumber, patientNumRaw, patientNum, conceptPath, null, FailureReason.MISSING_VALUE,
                    "Both NVAL_NUM and TVAL_CHAR are empty"
                );
                return null;
            }
            // The legacy loader's rule: text that parses as a number is a number
            numericValue = parseFinite(text);
            if (numericValue == null) {
                textValue = text;
            } else {
                numericText = text;
            }
        }

        Instant timestamp = null;
        String timestampRaw = record.size() > 4 ? record.get(4) : null;
        try {
            timestamp = TimestampParser.parse(timestampRaw);
        } catch (DateTimeParseException e) {
            // Non-fatal: the row is ingested without a timestamp, and the failure is recorded (capped per file).
            FileStats stats = fileStats(filePath);
            if (stats.warned.get(FailureReason.TIMESTAMP_PARSE_ERROR.ordinal()) == 0) {
                stats.firstTimestampSample = timestampRaw;
                log.warn(
                    "Cannot parse timestamp '{}' for patient {} in {}, continuing without timestamp (further failures counted, summary at file completion)",
                    timestampRaw, patientNum, filePath.getFileName()
                );
            }
            warn(
                filePath, context, recordNumber, patientNumRaw, patientNum, conceptPath, null, FailureReason.TIMESTAMP_PARSE_ERROR,
                "Cannot parse timestamp; ingested without one", timestampRaw
            );
        }

        return new ObservationRow(patientNum, conceptPath, numericValue, textValue, timestamp, numericText);
    }

    private FileStats fileStats(Path filePath) {
        return fileStats.computeIfAbsent(filePath, p -> new FileStats());
    }

    /** Ends the bookkeeping of a file: logs the tallies, writes the summary records for capped reasons, and returns the result. */
    private FileResult finishFile(Path filePath) {
        FileStats stats = fileStats.remove(filePath);
        if (stats == null) {
            return new FileResult(0, 0, Map.of(), Map.of());
        }
        long timestampFailures = stats.warned.get(FailureReason.TIMESTAMP_PARSE_ERROR.ordinal());
        if (timestampFailures > 0) {
            log.warn(
                "File {}: {} timestamp values could not be parsed and were ingested as null (first sample: '{}')", filePath.getFileName(),
                timestampFailures, stats.firstTimestampSample
            );
        }
        long bothValues = stats.warned.get(FailureReason.BOTH_VALUES_TEXT_DISCARDED.ordinal());
        if (bothValues > 0) {
            log.warn(
                "File {}: {} rows had both a numeric and a text value; the numeric value was kept and the text value discarded",
                filePath.getFileName(), bothValues
            );
        }
        for (FailureReason reason : FailureReason.values()) {
            long count = stats.warned.get(reason.ordinal());
            if (count > MAX_WARNINGS_PER_REASON_PER_FILE) {
                recordFailure(
                    filePath, RecordContext.NONE, null, null, null, null, null, null, reason,
                    "Summary: " + count + " rows in this file had this problem (" + reason.getDescription() + "). Only the first "
                        + MAX_WARNINGS_PER_REASON_PER_FILE + " are recorded individually; " + (count - MAX_WARNINGS_PER_REASON_PER_FILE)
                        + " more were not."
                );
            }
        }
        return stats.result();
    }

    /** A record was dropped: count it and write its failure record. */
    private void reject(
        Path file, RecordContext context, Long recordNumber, String participantId, Integer patientNum, String conceptPath, String valueRaw,
        FailureReason reason, String detail
    ) {
        fileStats(file).rejected.incrementAndGet(reason.ordinal());
        recordFailure(file, context, recordNumber, participantId, patientNum, conceptPath, null, valueRaw, reason, detail);
    }

    private void warn(
        Path file, RecordContext context, long recordNumber, String participantId, Integer patientNum, String conceptPath, String valueRaw,
        FailureReason reason, String detail
    ) {
        warn(file, context, recordNumber, participantId, patientNum, conceptPath, valueRaw, reason, detail, null);
    }

    /** A row was kept but noted: always counted, written individually only up to the per-file cap. */
    private void warn(
        Path file, RecordContext context, long recordNumber, String participantId, Integer patientNum, String conceptPath, String valueRaw,
        FailureReason reason, String detail, String timestampRaw
    ) {
        long seen = fileStats(file).warned.incrementAndGet(reason.ordinal());
        if (seen <= MAX_WARNINGS_PER_REASON_PER_FILE) {
            recordFailure(file, context, recordNumber, participantId, patientNum, conceptPath, timestampRaw, valueRaw, reason, detail);
        }
    }

    private void recordFailure(
        Path file, RecordContext context, Long recordNumber, String participantId, Integer patientNum, String conceptPath,
        String timestampRaw, String valueRaw, FailureReason reason, String detail
    ) {
        failureSink.recordFailure(
            new FailureRecord(
                runId, file.getFileName().toString(), file.toString(), recordNumber, participantId, patientNum, conceptPath, timestampRaw,
                valueRaw, reason, detail, context.chunkIndex(), context.chunkStartOffset()
            )
        );
    }
}
