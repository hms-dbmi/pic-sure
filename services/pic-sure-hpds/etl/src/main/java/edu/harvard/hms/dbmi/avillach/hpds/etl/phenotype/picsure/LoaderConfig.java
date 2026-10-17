package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Tuning knobs of the PICSURELoader, read from {@code -D} system properties. Replaces the Spring {@code @ConfigurationProperties} of the
 * hpds-ingest service and keeps its defaults.
 *
 * <table> <caption>System properties</caption> <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 * <tr><td>{@code picsure.loader.spoolDir}</td><td>none (the driver picks a directory next to the output)</td><td>where evicted concepts are
 * spooled; use fast local disk</td></tr> <tr><td>{@code picsure.loader.batchSize}</td><td>10000</td><td>rows per batch handed to the
 * writer</td></tr> <tr><td>{@code picsure.loader.storeCacheSize}</td><td>16</td><td>concepts kept in memory by the writer</td></tr>
 * <tr><td>{@code picsure.loader.fileProcessingThreads}</td><td>12</td><td>files processed concurrently</td></tr>
 * <tr><td>{@code picsure.loader.maxObservationsPerConcept}</td><td>5000000</td><td>observations kept per concept; the rest are rejected and
 * reported in concept_rejections.jsonl</td></tr> <tr><td>{@code picsure.loader.finalizationConcurrency}</td><td>12</td><td>concepts merged
 * and written in parallel at the end</td></tr> <tr><td>{@code picsure.loader.finalizationChunkSize}</td><td>1000</td><td>concepts finalized
 * per round</td></tr> </table>
 *
 * @param spoolDir spool directory, or null to let the driver choose
 */
public record LoaderConfig(
    Path spoolDir, int batchSize, int storeCacheSize, int fileProcessingThreads, int maxObservationsPerConcept, int finalizationConcurrency,
    int finalizationChunkSize
) {

    public static final String PREFIX = "picsure.loader.";

    public static final int DEFAULT_BATCH_SIZE = 10_000;
    public static final int DEFAULT_STORE_CACHE_SIZE = 16;
    public static final int DEFAULT_FILE_PROCESSING_THREADS = 12;
    public static final int DEFAULT_MAX_OBSERVATIONS_PER_CONCEPT = 5_000_000;
    public static final int DEFAULT_FINALIZATION_CONCURRENCY = 12;
    public static final int DEFAULT_FINALIZATION_CHUNK_SIZE = 1000;

    public LoaderConfig {
        requirePositive("batchSize", batchSize);
        requirePositive("storeCacheSize", storeCacheSize);
        requirePositive("fileProcessingThreads", fileProcessingThreads);
        requirePositive("maxObservationsPerConcept", maxObservationsPerConcept);
        requirePositive("finalizationConcurrency", finalizationConcurrency);
        requirePositive("finalizationChunkSize", finalizationChunkSize);
    }

    public static LoaderConfig defaults() {
        return fromProperties(key -> null);
    }

    public static LoaderConfig fromSystemProperties() {
        return fromProperties(System::getProperty);
    }

    /**
     * @param properties looks up a full property name, returning null when unset or blank
     * @throws IllegalArgumentException if a value is not a positive integer
     */
    public static LoaderConfig fromProperties(Function<String, String> properties) {
        String spool = properties.apply(PREFIX + "spoolDir");
        return new LoaderConfig(
            spool == null || spool.isBlank() ? null : Path.of(spool.trim()), intProperty(properties, "batchSize", DEFAULT_BATCH_SIZE),
            intProperty(properties, "storeCacheSize", DEFAULT_STORE_CACHE_SIZE),
            intProperty(properties, "fileProcessingThreads", DEFAULT_FILE_PROCESSING_THREADS),
            intProperty(properties, "maxObservationsPerConcept", DEFAULT_MAX_OBSERVATIONS_PER_CONCEPT),
            intProperty(properties, "finalizationConcurrency", DEFAULT_FINALIZATION_CONCURRENCY),
            intProperty(properties, "finalizationChunkSize", DEFAULT_FINALIZATION_CHUNK_SIZE)
        );
    }

    private static int intProperty(Function<String, String> properties, String name, int defaultValue) {
        String raw = properties.apply(PREFIX + name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + name + " must be an integer, got '" + raw + "'", e);
        }
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(PREFIX + name + " must be at least 1, got " + value);
        }
    }
}
