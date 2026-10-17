package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSONL failure sink for Splunk/Elastic consumption.
 *
 * <p>Thread-safe. Writes one JSON object per line and nothing else, so the file stays valid JSONL; the per-reason totals are available from
 * {@link #getRollup()} for the run summary.
 */
public class FailureSink implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FailureSink.class);

    private final Path outputFile;
    private final BufferedWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<FailureReason, AtomicLong> rollupCounters = new ConcurrentHashMap<>();
    private final AtomicLong totalFailures = new AtomicLong(0);

    public FailureSink(Path outputFile) throws IOException {
        this.outputFile = outputFile;
        this.writer =
            Files.newBufferedWriter(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Initialized failure sink: {}", outputFile);
    }

    /**
     * Records a failure (thread-safe).
     *
     * @throws UncheckedIOException if the record cannot be written: losing failure records silently would hide data problems
     */
    public synchronized void recordFailure(FailureRecord record) {
        try {
            writer.write(mapper.writeValueAsString(record));
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write failure record to " + outputFile, e);
        }
        rollupCounters.computeIfAbsent(record.reasonCode(), k -> new AtomicLong()).incrementAndGet();
        totalFailures.incrementAndGet();
    }

    /** Failure counts per reason so far. */
    public Map<FailureReason, Long> getRollup() {
        Map<FailureReason, Long> rollup = new TreeMap<>();
        rollupCounters.forEach((reason, count) -> rollup.put(reason, count.get()));
        return rollup;
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }

    public Path getOutputFile() {
        return outputFile;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
        log.info("Closed failure sink {}: {} failures {}", outputFile, totalFailures.get(), getRollup());
    }
}
