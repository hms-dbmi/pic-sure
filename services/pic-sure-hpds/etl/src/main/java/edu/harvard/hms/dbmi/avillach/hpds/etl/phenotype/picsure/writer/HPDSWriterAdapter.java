package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade between the observation producers and the {@link SpoolingLoadingStore}.
 *
 * <p>Thread-safety: {@link #acceptBatch} may be called concurrently; {@link #closeAndSave()} must be called once, after the last batch.
 *
 * <p>Nothing is dropped silently: every row that is not stored is counted in {@link #getStats()}, and a failure to store a row (for example
 * a full spool disk) is thrown to the caller instead of being logged and skipped.
 */
public class HPDSWriterAdapter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HPDSWriterAdapter.class);

    /** Counts of what happened to the rows passed to {@link #acceptBatch}. */
    public record WriterStats(long accepted, long invalid, long rejectedConceptLimit, long skippedNullSentinel) {
    }

    private final SpoolingLoadingStore store;
    private final AtomicBoolean finalizing = new AtomicBoolean(false);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong rejectedConceptLimit = new AtomicLong();
    private final AtomicLong skippedNullSentinel = new AtomicLong();

    public HPDSWriterAdapter(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize
    ) {
        this(
            new SpoolingLoadingStore(
                spoolDirectory, outputDirectory, encryptionKeyName, cacheSize, maxObservationsPerConcept, finalizationConcurrency,
                finalizationChunkSize
            )
        );
        log.info(
            "Initialized HPDS writer adapter: spool={}, output={}, cacheSize={}, maxObsPerConcept={}, finalizationConcurrency={}, chunkSize={}",
            spoolDirectory, outputDirectory, cacheSize, maxObservationsPerConcept, finalizationConcurrency, finalizationChunkSize
        );
    }

    HPDSWriterAdapter(SpoolingLoadingStore store) {
        this.store = store;
    }

    /**
     * Adds a batch of observations. A row must carry exactly one value (numeric or text); a row with none or both is counted as invalid and
     * not stored (the producers resolve that before rows get here).
     *
     * @return the number of rows stored
     */
    public int acceptBatch(List<ObservationRow> batch) {
        if (finalizing.get()) {
            throw new IllegalStateException("Writer adapter is closed");
        }
        int stored = 0;
        for (ObservationRow row : batch) {
            if (!row.isValid()) {
                if (invalid.getAndIncrement() == 0) {
                    log.warn(
                        "Invalid observation (must have exactly one value): patientNum={}, conceptPath={} (further occurrences counted only)",
                        row.patientNum(), row.conceptPath()
                    );
                }
                continue;
            }
            Date timestamp = row.dateTime() != null ? Date.from(row.dateTime()) : null;
            switch (store.addObservation(row.patientNum(), row.conceptPath(), row.getValue(), timestamp)) {
                case ADDED -> {
                    accepted.incrementAndGet();
                    stored++;
                }
                case REJECTED_CONCEPT_LIMIT -> rejectedConceptLimit.incrementAndGet();
                case SKIPPED_NULL_SENTINEL -> skippedNullSentinel.incrementAndGet();
            }
        }
        return stored;
    }

    /** Merges the spooled partials and writes allObservationsStore.javabin, columnMeta.javabin and columnMeta.csv. Call once. */
    public synchronized void closeAndSave() throws IOException {
        if (!finalizing.compareAndSet(false, true)) {
            log.warn("Writer adapter already closed");
            return;
        }
        long start = System.currentTimeMillis();
        log.atInfo().addKeyValue("event_type", "writer.finalization.started").addKeyValue("event_schema_version", "1.0")
            .log("Closing and finalizing HPDS writer adapter");
        try {
            store.saveStore();
        } catch (IOException | RuntimeException e) {
            log.atError().addKeyValue("event_type", "writer.finalization.failed").addKeyValue("event_schema_version", "1.0")
                .addKeyValue("elapsed_ms", System.currentTimeMillis() - start).addKeyValue("error_message", e.getMessage()).setCause(e)
                .log("Failed to finalize HPDS stores");
            throw e;
        }
        log.atInfo().addKeyValue("event_type", "writer.finalization.completed").addKeyValue("event_schema_version", "1.0")
            .addKeyValue("elapsed_ms", System.currentTimeMillis() - start).addKeyValue("total_patients", getPatientCount())
            .log("HPDS writer adapter closed ({} ms)", System.currentTimeMillis() - start);
    }

    @Override
    public void close() throws IOException {
        closeAndSave();
    }

    public int getPatientCount() {
        return store.getAllIds().size();
    }

    public int getConceptCount() {
        return store.getConceptCount();
    }

    public Set<Integer> getPatientIds() {
        return store.getAllIds();
    }

    /** Concepts the store could not write, with the reason; non-empty means the load is incomplete. Valid after {@link #closeAndSave()}. */
    public Map<String, String> getFailedConcepts() {
        return store.getFailedConcepts();
    }

    public WriterStats getStats() {
        return new WriterStats(accepted.get(), invalid.get(), rejectedConceptLimit.get(), skippedNullSentinel.get());
    }
}
