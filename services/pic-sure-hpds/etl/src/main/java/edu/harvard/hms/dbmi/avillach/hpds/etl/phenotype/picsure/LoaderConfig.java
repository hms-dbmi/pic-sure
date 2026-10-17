package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Tuning knobs of the PICSURELoader, read from {@code -D} system properties. Replaces the Spring {@code @ConfigurationProperties} of the
 * hpds-ingest service.
 *
 * <table> <caption>System properties</caption> <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 * <tr><td>{@code picsure.loader.spoolDir}</td><td>{@code <base>/.picsure-spool}</td><td>root of the spool; each partition spools under
 * {@code <spoolDir>/<partition>}, removed when the partition is done. Use fast local disk</td></tr>
 * <tr><td>{@code picsure.loader.batchSize}</td><td>10000</td><td>rows per batch handed to the writer</td></tr>
 * <tr><td>{@code picsure.loader.storeCacheSize}</td><td>16</td><td>concepts kept in memory by the writer</td></tr>
 * <tr><td>{@code picsure.loader.fileProcessingThreads}</td><td>12</td><td>kept for compatibility with the ingest-service; not used, because
 * a partition is one file and a large file is already parsed in parallel chunks</td></tr>
 * <tr><td>{@code picsure.loader.maxObservationsPerConcept}</td><td>0 (unlimited)</td><td>observations kept per concept. A concept that
 * reaches a non-zero limit rejects the rest, is listed in concept_rejections.jsonl and <b>fails the partition</b>: nothing is truncated
 * silently</td></tr> <tr><td>{@code picsure.loader.finalizationConcurrency}</td><td>12</td><td>upper bound of concepts merged and written
 * in parallel at the end; a heap budget (half of the max heap, about 400 bytes per observation) gates them further, so a few huge concepts
 * run one at a time</td></tr> <tr><td>{@code picsure.loader.finalizationChunkSize}</td><td>1000</td><td>concepts finalized per
 * round</td></tr> <tr><td>{@code picsure.loader.dedup}</td><td>false</td><td>drop exact duplicate rows (same patient, concept, value and
 * timestamp). False keeps them, as {@code CSVLoader} does</td></tr> <tr><td>{@code picsure.loader.failFast}</td><td>true</td><td>stop at
 * the first failed partition; false loads the remaining partitions too (the exit code is non-zero either way)</td></tr>
 * <tr><td>{@code picsure.loader.overwrite}</td><td>false</td><td>replace a store that is already in a partition directory; false refuses
 * before any work</td></tr> <tr><td>{@code picsure.loader.runId}</td><td>generated</td><td>run id written into failures.jsonl and
 * ingest-summary.json</td></tr> </table>
 *
 * @param spoolDir spool root, or null to use {@code <base>/.picsure-spool}
 * @param maxObservationsPerConcept 0 = unlimited
 */
public record LoaderConfig(
    Path spoolDir, int batchSize, int storeCacheSize, int fileProcessingThreads, int maxObservationsPerConcept, int finalizationConcurrency,
    int finalizationChunkSize, boolean dedup, boolean failFast, boolean overwrite, String runId
) {

    public static final String PREFIX = "picsure.loader.";

    public static final int DEFAULT_BATCH_SIZE = 10_000;
    public static final int DEFAULT_STORE_CACHE_SIZE = 16;
    public static final int DEFAULT_FILE_PROCESSING_THREADS = 12;
    public static final int DEFAULT_MAX_OBSERVATIONS_PER_CONCEPT = 0;
    public static final int DEFAULT_FINALIZATION_CONCURRENCY = 12;
    public static final int DEFAULT_FINALIZATION_CHUNK_SIZE = 1000;

    public LoaderConfig {
        requirePositive("batchSize", batchSize);
        requirePositive("storeCacheSize", storeCacheSize);
        requirePositive("fileProcessingThreads", fileProcessingThreads);
        if (maxObservationsPerConcept < 0) {
            throw new IllegalArgumentException(
                PREFIX + "maxObservationsPerConcept must be 0 (unlimited) or more, got " + maxObservationsPerConcept
            );
        }
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
     * @throws IllegalArgumentException if a value is not a valid integer, boolean or in range
     */
    public static LoaderConfig fromProperties(Function<String, String> properties) {
        String spool = properties.apply(PREFIX + "spoolDir");
        String runId = properties.apply(PREFIX + "runId");
        return new LoaderConfig(
            spool == null || spool.isBlank() ? null : Path.of(spool.trim()), intProperty(properties, "batchSize", DEFAULT_BATCH_SIZE),
            intProperty(properties, "storeCacheSize", DEFAULT_STORE_CACHE_SIZE),
            intProperty(properties, "fileProcessingThreads", DEFAULT_FILE_PROCESSING_THREADS),
            intProperty(properties, "maxObservationsPerConcept", DEFAULT_MAX_OBSERVATIONS_PER_CONCEPT),
            intProperty(properties, "finalizationConcurrency", DEFAULT_FINALIZATION_CONCURRENCY),
            intProperty(properties, "finalizationChunkSize", DEFAULT_FINALIZATION_CHUNK_SIZE), booleanProperty(properties, "dedup", false),
            booleanProperty(properties, "failFast", true), booleanProperty(properties, "overwrite", false),
            runId == null || runId.isBlank() ? null : runId.trim()
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

    private static boolean booleanProperty(Function<String, String> properties, String name, boolean defaultValue) {
        String raw = properties.apply(PREFIX + name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(PREFIX + name + " must be true or false, got '" + raw + "'");
        };
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(PREFIX + name + " must be at least 1, got " + value);
        }
    }
}
