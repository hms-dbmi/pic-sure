package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Tuning knobs of the PICSURELoader, read from {@code -D} system properties. Replaces the Spring {@code @ConfigurationProperties} of the
 * hpds-ingest service.
 *
 * <table> <caption>System properties</caption> <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 * <tr><td>{@code picsure.loader.spoolDir}</td><td>split input: {@code <base>/.picsure-spool}; combined input:
 * {@code <parent of base>/<base name>.picsure-spool}</td><td>root of the spool; each partition spools under {@code <spoolDir>/<partition>},
 * removed when the partition is done; a combined input also routes into {@code <spoolDir>/.routed}. With a combined input it may not be
 * inside the base (HPDS reads every subdirectory of the base as a partition). Use fast local disk</td></tr>
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
 * ingest-summary.json</td></tr> <tr><td>{@code picsure.loader.input}</td><td>unset (split input)</td><td>one combined allConcepts file (any
 * name) that is routed to the partitions by consent; unset reads the {@code <partition>-allConcepts.csv} files under the base</td></tr>
 * <tr><td>{@code picsure.loader.partitionBy}</td><td>{@code consent} for a combined input</td><td>{@code consent}: one partition per
 * consent; {@code none}: one flat store in the base (parity checks only). Invalid without {@code input}</td></tr>
 * <tr><td>{@code picsure.loader.keepRouted}</td><td>false</td><td>keep the routed files of a combined input after the run (success or
 * failure); false deletes the routed directory on every exit path. Kept files are outside the base</td></tr>
 * <tr><td>{@code picsure.loader.maxRejectedConsentRows}</td><td>0</td><td>combined input: rows of {@code \_consents\} that grant nothing
 * (wrong field count, patient id, nval, timestamp, a path that is not written exactly {@code \_consents\}) tolerated; more ends the run
 * before any partition loads. Each such row still makes its study known, so the study's rows of patients without a valid consent of it are
 * dropped. A consents row on a line that cannot be parsed ends the run whatever this is</td></tr>
 * <tr><td>{@code picsure.loader.maxOpenWriters}</td><td>min(1024, open-file limit - 256), 128 if the limit is unknown</td><td>routed files
 * open at once while routing; beyond it the least recently used is closed and reopened for append (slower, order kept)</td></tr> </table>
 *
 * @param maxRejectedRows rows dropped for a reason that would have stopped {@code CSVLoader} (see {@link FailureReason#crashesCsvLoader()})
 *        that a partition tolerates; more fails it
 * @param maxRejectedConsentRows rows of the consents variable that grant nothing (a combined input; see {@link ConsentRouter}) that the
 *        routing tolerates; more refuses the run before any partition loads. Independent of {@code maxRejectedRows}. A consents row on a
 *        line that cannot be parsed is never tolerated, whatever this is
 * @param maxTimestampFailures timestamps that cannot be parsed (the row is kept without one) that a partition tolerates; more fails it
 * @param chunkConcurrency chunks of a large file parsed at once, 0 = derive (see {@link #effectiveChunkConcurrency()})
 * @param spoolDir spool root, or null to use {@code <base>/.picsure-spool}
 * @param input the combined input file, or null for split input
 * @param partitionBy {@link #PARTITION_BY_CONSENT} or {@link #PARTITION_BY_NONE}, or null when not set
 * @param keepRouted keep the routed directory of a combined input after the run
 * @param maxOpenWriters routed files open at once while routing, 0 = derive (see {@link #effectiveMaxOpenWriters()})
 * @param maxObservationsPerConcept 0 = unlimited
 */
public record LoaderConfig(
    Path spoolDir, int batchSize, int storeCacheSize, int fileProcessingThreads, int maxObservationsPerConcept, int finalizationConcurrency,
    int finalizationChunkSize, boolean dedup, boolean failFast, boolean overwrite, String runId, int maxRejectedRows,
    int maxRejectedConsentRows, int maxTimestampFailures, int chunkConcurrency, Path input, String partitionBy, boolean keepRouted,
    int maxOpenWriters
) {

    public static final String PREFIX = "picsure.loader.";

    public static final String PARTITION_BY_CONSENT = "consent";
    public static final String PARTITION_BY_NONE = "none";

    public static final int DEFAULT_BATCH_SIZE = 10_000;
    public static final int DEFAULT_STORE_CACHE_SIZE = 16;
    public static final int DEFAULT_FILE_PROCESSING_THREADS = 12;
    public static final int DEFAULT_MAX_OBSERVATIONS_PER_CONCEPT = 0;
    public static final int DEFAULT_FINALIZATION_CONCURRENCY = 12;
    public static final int DEFAULT_FINALIZATION_CHUNK_SIZE = 1000;
    /** Open routed files when the limit of open files is unknown, and the ceiling when it is known. */
    public static final int FALLBACK_MAX_OPEN_WRITERS = 128;
    public static final int CEILING_MAX_OPEN_WRITERS = 1024;
    /** Descriptors left for everything else (the input, the failure files, the stores, the JVM). */
    public static final int RESERVED_DESCRIPTORS = 256;

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
        if (maxRejectedRows < 0 || maxRejectedConsentRows < 0 || maxTimestampFailures < 0 || chunkConcurrency < 0) {
            throw new IllegalArgumentException(
                PREFIX + "maxRejectedRows, maxRejectedConsentRows, maxTimestampFailures and chunkConcurrency may not be negative"
            );
        }
        if (maxOpenWriters < 0) {
            throw new IllegalArgumentException(PREFIX + "maxOpenWriters may not be negative, got " + maxOpenWriters);
        }
        if (partitionBy != null && !PARTITION_BY_CONSENT.equals(partitionBy) && !PARTITION_BY_NONE.equals(partitionBy)) {
            throw new IllegalArgumentException(
                PREFIX + "partitionBy must be " + PARTITION_BY_CONSENT + " or " + PARTITION_BY_NONE + ", got '" + partitionBy + "'"
            );
        }
    }

    /** True when one combined file is the input ({@code picsure.loader.input}) instead of the split files under the base. */
    public boolean combinedInput() {
        return input != null;
    }

    /** {@code partitionBy} with its default: {@code consent}. Only meaningful for a combined input. */
    public String effectivePartitionBy() {
        return partitionBy != null ? partitionBy : PARTITION_BY_CONSENT;
    }

    /** Chunks of a large file parsed at once: the configured value, or min(cores, storeCacheSize/2) and at least 1. */
    public int effectiveChunkConcurrency() {
        return chunkConcurrency > 0 ? chunkConcurrency
            : Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), storeCacheSize / 2));
    }

    /** The configured routed files open at once, or min(1024, open-file limit - 256) and at least 16; 128 when the limit is unknown. */
    public int effectiveMaxOpenWriters() {
        return maxOpenWriters > 0 ? maxOpenWriters : defaultMaxOpenWriters();
    }

    static int defaultMaxOpenWriters() {
        if (
            java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.UnixOperatingSystemMXBean unix
        ) {
            long limit = unix.getMaxFileDescriptorCount();
            if (limit > 0) {
                return (int) Math.max(16, Math.min(CEILING_MAX_OPEN_WRITERS, limit - RESERVED_DESCRIPTORS));
            }
        }
        return FALLBACK_MAX_OPEN_WRITERS;
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
        String input = properties.apply(PREFIX + "input");
        String partitionBy = properties.apply(PREFIX + "partitionBy");
        return new LoaderConfig(
            spool == null || spool.isBlank() ? null : Path.of(spool.trim()), intProperty(properties, "batchSize", DEFAULT_BATCH_SIZE),
            intProperty(properties, "storeCacheSize", DEFAULT_STORE_CACHE_SIZE),
            intProperty(properties, "fileProcessingThreads", DEFAULT_FILE_PROCESSING_THREADS),
            intProperty(properties, "maxObservationsPerConcept", DEFAULT_MAX_OBSERVATIONS_PER_CONCEPT),
            intProperty(properties, "finalizationConcurrency", DEFAULT_FINALIZATION_CONCURRENCY),
            intProperty(properties, "finalizationChunkSize", DEFAULT_FINALIZATION_CHUNK_SIZE), booleanProperty(properties, "dedup", false),
            booleanProperty(properties, "failFast", true), booleanProperty(properties, "overwrite", false),
            runId == null || runId.isBlank() ? null : runId.trim(), nonNegativeIntProperty(properties, "maxRejectedRows", 0),
            nonNegativeIntProperty(properties, "maxRejectedConsentRows", 0), nonNegativeIntProperty(properties, "maxTimestampFailures", 0),
            nonNegativeIntProperty(properties, "chunkConcurrency", 0), input == null || input.isBlank() ? null : Path.of(input.trim()),
            partitionBy == null || partitionBy.isBlank() ? null : partitionBy.trim().toLowerCase(java.util.Locale.ROOT),
            booleanProperty(properties, "keepRouted", false), nonNegativeIntProperty(properties, "maxOpenWriters", 0)
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

    private static int nonNegativeIntProperty(Function<String, String> properties, String name, int defaultValue) {
        int value = intProperty(properties, name, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(PREFIX + name + " may not be negative, got " + value);
        }
        return value;
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
