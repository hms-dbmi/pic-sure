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
 * {@link #close()} never finalizes: it removes the spool of a store that was not saved, so a failed load leaves no store behind.
 *
 * <p>Nothing is dropped silently: every row that is not stored is counted in {@link #getStats()}, and a failure to store a row (for example
 * a full spool disk) is thrown to the caller instead of being logged and skipped. After {@link #closeAndSave()} the caller must check
 * {@link #getFailedConcepts()} and {@code getStats().rejectedConceptLimit()}: either being non-zero means the store is incomplete.
 */
public class HPDSWriterAdapter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HPDSWriterAdapter.class);

    /**
     * Counts of what happened to the rows passed to {@link #acceptBatch}; final after {@link #closeAndSave()}.
     *
     * @param accepted rows put into a concept, before duplicate removal
     * @param invalid rows with no value or two values (the producers resolve that before rows get here)
     * @param rejectedConceptLimit rows refused because their concept reached the observation limit
     * @param skippedNullSentinel null sentinels dropped (see {@link SpoolingLoadingStore})
     * @param duplicatesDropped exact duplicates removed (only with dedup)
     */
    public record WriterStats(long accepted, long invalid, long rejectedConceptLimit, long skippedNullSentinel, long duplicatesDropped) {
    }

    private final SpoolingLoadingStore store;
    private final AtomicBoolean finalizing = new AtomicBoolean(false);
    private final AtomicLong invalid = new AtomicLong();

    public HPDSWriterAdapter(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize
    ) {
        this(
            spoolDirectory, outputDirectory, encryptionKeyName, cacheSize, maxObservationsPerConcept, finalizationConcurrency,
            finalizationChunkSize, false
        );
    }

    /**
     * @param maxObservationsPerConcept 0 = unlimited
     * @param dedup drop exact duplicate rows at finalization
     */
    public HPDSWriterAdapter(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize, boolean dedup
    ) {
        this(
            new SpoolingLoadingStore(
                spoolDirectory, outputDirectory, encryptionKeyName, cacheSize, maxObservationsPerConcept, finalizationConcurrency,
                finalizationChunkSize, dedup
            )
        );
        log.info(
            "Initialized HPDS writer adapter: spool={}, output={}, cacheSize={}, maxObsPerConcept={}, finalizationConcurrency={}, chunkSize={}, dedup={}",
            spoolDirectory, outputDirectory, cacheSize, maxObservationsPerConcept, finalizationConcurrency, finalizationChunkSize, dedup
        );
    }

    HPDSWriterAdapter(SpoolingLoadingStore store) {
        this.store = store;
    }

    /**
     * Adds a batch of observations. A row must carry exactly one value (numeric or text); a row with none or both is counted as invalid and
     * not stored (the producers resolve that before rows get here).
     *
     * @return the number of rows handed to the store without being refused or skipped on the spot (held null sentinels count here; see
     *         {@link #getStats()} for the final outcome)
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
                case ADDED, HELD_NULL_SENTINEL -> stored++;
                case REJECTED_CONCEPT_LIMIT -> {
                }
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

    /**
     * Releases the store. A store that was not saved with {@link #closeAndSave()} is discarded (its spool is removed and nothing is
     * written): finalizing a partial store on a failure path would leave something that looks valid.
     */
    @Override
    public synchronized void close() {
        if (finalizing.compareAndSet(false, true)) {
            log.info("Writer adapter closed without saving: discarding the spool");
        }
        store.abort();
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

    /** Concepts that hit the observation limit, with the observations refused; non-empty means the load is incomplete. */
    public Map<String, Long> getRejectedConcepts() {
        return store.getRejectedConcepts();
    }

    /** Observations in the concepts that were written. Valid after {@link #closeAndSave()}. */
    public long getObservationsWritten() {
        return store.getObservationsWritten();
    }

    /** Partial files spooled so far: how often concepts were evicted from the in-memory cache. */
    public long getSpooledPartials() {
        return store.getSpooledPartials();
    }

    /** Observations accepted into concepts that could not be written. Valid after {@link #closeAndSave()}. */
    public long getFailedConceptObservations() {
        return store.getFailedConceptObservations();
    }

    public WriterStats getStats() {
        SpoolingLoadingStore.StoreStats stats = store.getStats();
        return new WriterStats(
            stats.accepted(), invalid.get(), stats.rejectedConceptLimit(), stats.skippedNullSentinel(), stats.duplicatesDropped()
        );
    }
}
