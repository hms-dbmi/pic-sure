package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util.ColumnMetaBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * HPDS phenotype store writer with spool-and-finalize semantics.
 *
 * <p>The legacy {@code LoadingStore} needs its input sorted by concept: a concept that is evicted from its 16-entry cache and then seen
 * again would be written a second time and overwrite its own metadata. This store instead: <ul> <li>accepts observations for any concept in
 * any order, keeping at most {@code cacheSize} concepts in memory;</li> <li>spools an evicted concept's raw observations to a compressed
 * partial file on disk (several partials per concept are fine);</li> <li>at {@link #saveStore()} merges all partials of each concept, drops
 * exact duplicates, sorts, and writes every concept exactly once to {@code allObservationsStore.javabin} (Java-serialized
 * {@link PhenoCube}, AES-GCM encrypted with {@code encryptionKeyName}), plus {@code columnMeta.javabin} / {@code columnMeta.csv}.</li>
 * </ul>
 *
 * <p>The output files use the same javabin format as the legacy loaders, so {@code PartitionedPhenotypicObservationStore} reads them
 * unchanged (use {@link Crypto#DEFAULT_KEY_NAME} as the key name for stores that service reads).
 *
 * <p>Thread-safety: {@link #addObservation} may be called concurrently. {@link #saveStore()} must be called once, after all adds.
 */
public class SpoolingLoadingStore {
    private static final Logger log = LoggerFactory.getLogger(SpoolingLoadingStore.class);

    /** Observations beyond this per-concept count cannot be merged into a single Java array (10M safety buffer below Integer.MAX_VALUE). */
    static final long DEFAULT_MAX_MERGED_OBSERVATIONS = Integer.MAX_VALUE - 10_000_000L;
    /** AES-GCM encryption works on a single byte[], so a serialized concept must stay below this (100 MB buffer for the cipher). */
    static final long DEFAULT_MAX_SERIALIZED_BYTES = Integer.MAX_VALUE - 100_000_000L;

    private static final int MAX_CACHE_RETRIES = 1000;

    /** What happened to an observation passed to {@link #addObservation}. */
    public enum AddResult {
        /** Added to the concept. */
        ADDED,
        /** The concept already holds {@code maxObservationsPerConcept} observations; counted in the rejection report. */
        REJECTED_CONCEPT_LIMIT,
        /** A null-sentinel string ("NA", "None", ...) arriving for a numeric concept; skipped, not a value. */
        SKIPPED_NULL_SENTINEL
    }

    private final Path spoolDirectory;
    private final Path outputDirectory;
    private final String encryptionKeyName;
    private final int maxObservationsPerConcept;
    private final int finalizationConcurrency;
    private final int finalizationChunkSize;
    private final long maxMergedObservations;
    private final long maxSerializedBytes;

    private final NullSentinelDetector nullSentinelDetector = new NullSentinelDetector();

    /** Per-concept state; entries are never replaced, only mutated (a replaced entry would lose spool counters). */
    private final ConcurrentHashMap<String, ConceptMetadata> conceptMetadata = new ConcurrentHashMap<>();
    private final Set<Integer> allIds = Collections.synchronizedSet(new TreeSet<>());
    private final LoadingCache<String, ActiveCube> cache;
    private final ConcurrentHashMap<String, List<Path>> spoolFiles = new ConcurrentHashMap<>();
    private final Set<Path> spoolBucketDirs = ConcurrentHashMap.newKeySet();
    /** Serialises adds to one concept (different concepts proceed in parallel). */
    private final ConcurrentHashMap<String, ReentrantLock> conceptLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> conceptRejections = new ConcurrentHashMap<>();
    /** Guava swallows exceptions thrown by removal listeners; a failed spool is parked here and rethrown on the caller's thread. */
    private final AtomicReference<Throwable> spoolFailure = new AtomicReference<>();
    /** Concepts that could not be finalized (concept path to reason). Valid after {@link #saveStore()}. */
    private final Map<String, String> failedConcepts = new ConcurrentHashMap<>();

    private static final class ConceptMetadata {
        volatile boolean categorical;
        final AtomicInteger partialCount = new AtomicInteger();
        final AtomicLong observationCount = new AtomicLong();

        ConceptMetadata(boolean categorical) {
            this.categorical = categorical;
        }
    }

    /** A concept's in-memory observations. {@code spooled} is guarded by the instance monitor and prevents adds after eviction. */
    private static final class ActiveCube {
        final PhenoCube<?> cube;
        boolean spooled;

        ActiveCube(PhenoCube<?> cube) {
            this.cube = cube;
        }
    }

    public SpoolingLoadingStore(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize
    ) {
        this(
            spoolDirectory, outputDirectory, encryptionKeyName, cacheSize, maxObservationsPerConcept, finalizationConcurrency,
            finalizationChunkSize, DEFAULT_MAX_MERGED_OBSERVATIONS, DEFAULT_MAX_SERIALIZED_BYTES
        );
    }

    /** Package-private so tests can exercise the 2 GB guards with small limits. */
    SpoolingLoadingStore(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize, long maxMergedObservations, long maxSerializedBytes
    ) {
        if (cacheSize < 1 || maxObservationsPerConcept < 1 || finalizationConcurrency < 1 || finalizationChunkSize < 1) {
            throw new IllegalArgumentException(
                "cacheSize, maxObservationsPerConcept, finalizationConcurrency and finalizationChunkSize must be >= 1"
            );
        }
        if (!Crypto.hasKey(encryptionKeyName)) {
            throw new IllegalStateException("No encryption key loaded under the name '" + encryptionKeyName + "'");
        }
        this.spoolDirectory = spoolDirectory;
        this.outputDirectory = outputDirectory;
        this.encryptionKeyName = encryptionKeyName;
        this.maxObservationsPerConcept = maxObservationsPerConcept;
        this.finalizationConcurrency = finalizationConcurrency;
        this.finalizationChunkSize = finalizationChunkSize;
        this.maxMergedObservations = maxMergedObservations;
        this.maxSerializedBytes = maxSerializedBytes;

        try {
            Files.createDirectories(spoolDirectory);
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create spool/output directories", e);
        }

        this.cache = CacheBuilder.newBuilder().maximumSize(cacheSize).<String, ActiveCube>removalListener(notification -> {
            if (notification.getCause() != RemovalCause.SIZE && notification.getCause() != RemovalCause.EXPLICIT) {
                return;
            }
            ActiveCube active = notification.getValue();
            try {
                synchronized (active) {
                    if (!active.spooled) {
                        active.spooled = true;
                        if (active.cube.getLoadingMap() != null && !active.cube.getLoadingMap().isEmpty()) {
                            spoolPartial(notification.getKey(), active.cube);
                        }
                    }
                }
            } catch (Throwable t) {
                log.error("Failed to spool concept {}", notification.getKey(), t);
                spoolFailure.compareAndSet(null, t);
            }
        }).build(new CacheLoader<String, ActiveCube>() {
            @Override
            public ActiveCube load(String conceptPath) {
                ConceptMetadata meta = conceptMetadata.get(conceptPath);
                if (meta == null) {
                    throw new IllegalStateException("No metadata for concept: " + conceptPath);
                }
                return new ActiveCube(newCube(conceptPath, meta));
            }
        });
    }

    /**
     * Adds an observation to a concept. The concept's type (numeric or categorical) is fixed by its first observation. A text value
     * arriving for a numeric concept is parsed as a number when possible, skipped when it is a null sentinel, and otherwise promotes the
     * whole concept to categorical. A number arriving for a categorical concept is stored as text.
     *
     * @param value a {@link Double} or a {@link String}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public AddResult addObservation(int patientNum, String conceptPath, Comparable<?> value, Date timestamp) {
        if (!(value instanceof Double) && !(value instanceof String)) {
            throw new IllegalArgumentException("Observation value must be a Double or a String, got: " + value);
        }
        checkSpoolHealth();

        ReentrantLock lock = conceptLocks.computeIfAbsent(conceptPath, k -> new ReentrantLock());
        lock.lock();
        try {
            ConceptMetadata meta = conceptMetadata.computeIfAbsent(conceptPath, k -> new ConceptMetadata(value instanceof String));

            if (meta.observationCount.get() >= maxObservationsPerConcept) {
                long rejected = conceptRejections.computeIfAbsent(conceptPath, k -> new AtomicLong()).incrementAndGet();
                if (rejected == 1) {
                    log.atWarn().addKeyValue("event_type", "concept.limit.reached").addKeyValue("event_schema_version", "1.0")
                        .addKeyValue("concept_path", conceptPath).addKeyValue("observations_accepted", meta.observationCount.get())
                        .addKeyValue("limit_value", maxObservationsPerConcept).log(
                            "Concept '{}' reached observation limit ({}), rejecting further observations", conceptPath,
                            maxObservationsPerConcept
                        );
                }
                return AddResult.REJECTED_CONCEPT_LIMIT;
            }

            Comparable<?> coerced = value;
            if (meta.categorical && value instanceof Double) {
                coerced = value.toString();
            } else if (!meta.categorical && value instanceof String text) {
                if (nullSentinelDetector.isNullSentinel(text)) {
                    return AddResult.SKIPPED_NULL_SENTINEL;
                }
                try {
                    coerced = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    // Not a number and not a null marker: the concept is really categorical. Flip it in place (never replace the
                    // metadata: its partial counter and observation count must survive) and spool the numeric cube; already spooled
                    // numeric partials are converted to text when merged.
                    log.warn("Concept '{}' was numeric but received non-numeric text '{}': promoting to categorical", conceptPath, text);
                    meta.categorical = true;
                    cache.invalidate(conceptPath);
                    coerced = text;
                }
            }

            for (int attempt = 0; attempt < MAX_CACHE_RETRIES; attempt++) {
                ActiveCube active = getActive(conceptPath);
                synchronized (active) {
                    if (!active.spooled) {
                        ((PhenoCube) active.cube).add(patientNum, coerced, timestamp);
                        meta.observationCount.incrementAndGet();
                        allIds.add(patientNum);
                        return AddResult.ADDED;
                    }
                }
                // Evicted by another thread between get() and the monitor: fetch (and re-create) the cube.
            }
            throw new IllegalStateException("Could not obtain an active cube for concept: " + conceptPath);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PhenoCube<?> newCube(String conceptPath, ConceptMetadata meta) {
        return new PhenoCube(conceptPath, meta.categorical ? String.class : Double.class);
    }

    private ActiveCube getActive(String conceptPath) {
        try {
            return cache.get(conceptPath);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to create cube for concept: " + conceptPath, e.getCause());
        }
    }

    private void checkSpoolHealth() {
        Throwable failure = spoolFailure.get();
        if (failure != null) {
            throw new IllegalStateException("A concept could not be spooled to " + spoolDirectory + "; the load is incomplete", failure);
        }
    }

    /** Writes a partial (the raw observation list) of a concept to disk. Callers hold the cube's monitor. */
    private void spoolPartial(String conceptPath, PhenoCube<?> cube) {
        ConceptMetadata meta = conceptMetadata.get(conceptPath);
        long start = System.currentTimeMillis();
        try {
            int partialNum = meta.partialCount.getAndIncrement();
            Path spoolFile = getSpoolFilePath(conceptPath, partialNum);
            spoolFiles.computeIfAbsent(conceptPath, k -> Collections.synchronizedList(new ArrayList<>())).add(spoolFile);

            try (
                OutputStream fos = Files.newOutputStream(spoolFile); GZIPOutputStream gzos =
                    new GZIPOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(gzos)
            ) {
                oos.writeObject(cube.getLoadingMap());
            }
            log.atDebug().addKeyValue("event_type", "concept.spooling.completed").addKeyValue("event_schema_version", "1.0")
                .addKeyValue("concept_path", conceptPath).addKeyValue("spool_file_number", partialNum)
                .addKeyValue("observations_spooled", cube.getLoadingMap().size())
                .addKeyValue("elapsed_ms", System.currentTimeMillis() - start)
                .log("Spooled partial {} of concept {}", partialNum, conceptPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to spool partial for concept: " + conceptPath, e);
        }
    }

    /**
     * Two-level hash bucketing ({@code /spool/AB/CD/<sha256>.partial.N.gz}) keeps any directory below ~65K entries even with 200K+ concepts
     * and several partials each.
     */
    private Path getSpoolFilePath(String conceptPath, int partialNum) throws IOException {
        String hash = sha256Hex(conceptPath);
        Path bucketDir = spoolDirectory.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4));
        Files.createDirectories(bucketDir);
        spoolBucketDirs.add(bucketDir);
        return bucketDir.resolve(hash + ".partial." + partialNum + ".gz");
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Flushes the cache, merges the partials of every concept and writes the store files. A concept that cannot be finalized (for example
     * one over the 2 GB limit) is left out of the store and reported by {@link #getFailedConcepts()}; the other concepts are still written.
     *
     * @throws IOException if a spool file could not be written earlier or the store files cannot be written
     */
    public void saveStore() throws IOException {
        log.info("Finalizing store: flushing cache and merging spooled partials");
        cache.invalidateAll();
        cache.cleanUp();
        Throwable failure = spoolFailure.get();
        if (failure != null) {
            throw new IOException(
                "A concept could not be spooled to " + spoolDirectory + "; refusing to write an incomplete store", failure
            );
        }

        ConcurrentHashMap<String, ColumnMeta> metadataMap = new ConcurrentHashMap<>();
        List<String> allConcepts = new ArrayList<>(conceptMetadata.keySet());
        int totalConcepts = allConcepts.size();
        int totalChunks = (totalConcepts + finalizationChunkSize - 1) / finalizationChunkSize;
        long finalizationStart = System.currentTimeMillis();
        log.info(
            "Finalizing {} concepts in {} chunks of up to {} ({} concurrent)", totalConcepts, totalChunks, finalizationChunkSize,
            finalizationConcurrency
        );

        try (
            RandomAccessFile allObservationsStore =
                new RandomAccessFile(outputDirectory.resolve("allObservationsStore.javabin").toFile(), "rw")
        ) {
            allObservationsStore.setLength(0);
            for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                int startIdx = chunkIdx * finalizationChunkSize;
                int endIdx = Math.min(startIdx + finalizationChunkSize, totalConcepts);
                processChunk(allConcepts.subList(startIdx, endIdx), allObservationsStore, metadataMap);
                log.debug("Chunk {}/{} done: {}/{} concepts finalized", chunkIdx + 1, totalChunks, metadataMap.size(), totalConcepts);
            }
        }

        log.info(
            "Finalized {}/{} concepts in {} ms; {} failed", metadataMap.size(), totalConcepts,
            System.currentTimeMillis() - finalizationStart, failedConcepts.size()
        );
        if (!failedConcepts.isEmpty()) {
            failedConcepts.forEach((concept, reason) -> log.error("Concept NOT written to the store: {} | {}", concept, reason));
        }

        // Same layout as the legacy LoadingStore: TreeMap<String, ColumnMeta> followed by the TreeSet of all patient ids.
        TreeMap<String, ColumnMeta> sortedMetadata = new TreeMap<>(metadataMap);
        try (
            ObjectOutputStream metaOut =
                new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(outputDirectory.resolve("columnMeta.javabin"))))
        ) {
            metaOut.writeObject(sortedMetadata);
            synchronized (allIds) {
                metaOut.writeObject(new TreeSet<>(allIds));
            }
        }
        ColumnMetaBuilder.writeColumnMetaCsv(sortedMetadata, outputDirectory.resolve("columnMeta.csv"));

        cleanupSpool();
        writeConceptRejectionReport();
        log.info("Store finalized: {} concepts, {} patients", metadataMap.size(), allIds.size());
    }

    private void processChunk(List<String> chunk, RandomAccessFile store, ConcurrentHashMap<String, ColumnMeta> metadataMap) {
        ExecutorService executor = Executors.newFixedThreadPool(finalizationConcurrency, Thread.ofVirtual().factory());
        try {
            List<Future<?>> futures = new ArrayList<>(chunk.size());
            for (String conceptPath : chunk) {
                futures.add(executor.submit(() -> {
                    try {
                        ColumnMeta columnMeta = finalizeConcept(conceptPath, store);
                        if (columnMeta != null) {
                            metadataMap.put(conceptPath, columnMeta);
                        }
                    } catch (Throwable t) {
                        String reason = t.getClass().getSimpleName() + ": " + t.getMessage();
                        log.error("Failed to finalize concept: {} | {}", conceptPath, reason, t);
                        failedConcepts.put(conceptPath, reason);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while finalizing concepts", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Concept finalization task failed", e);
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Merges, completes, serializes, encrypts and appends one concept. The only serialized step is the file append.
     *
     * @return the concept's metadata, or null if it ended up with no observations
     */
    private ColumnMeta finalizeConcept(String conceptPath, RandomAccessFile store) throws IOException {
        long start = System.currentTimeMillis();
        ConceptMetadata meta = conceptMetadata.get(conceptPath);
        PhenoCube<?> cube = mergePartials(conceptPath, meta);
        if (cube.getLoadingMap().isEmpty()) {
            log.warn("Concept {} has no observations; not written", conceptPath);
            return null;
        }
        complete(cube);

        int width = cube.isStringType() ? categoricalWidth(cube) : Double.BYTES;
        cube.setColumnWidth(width);
        ColumnMeta columnMeta = ColumnMetaBuilder.fromPhenoCube(cube, width);

        byte[] encrypted = serializeAndEncrypt(conceptPath, cube);
        synchronized (store) {
            columnMeta.setAllObservationsOffset(store.getFilePointer());
            store.write(encrypted);
            columnMeta.setAllObservationsLength(store.getFilePointer());
        }
        log.debug(
            "Finalized concept {} ({} observations) in {} ms", conceptPath, cube.sortedByKey().length, System.currentTimeMillis() - start
        );
        return columnMeta;
    }

    private byte[] serializeAndEncrypt(String conceptPath, PhenoCube<?> cube) throws IOException {
        byte[] serialized;
        try (
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(estimateSerializationSize(cube)); ObjectOutputStream out =
                new ObjectOutputStream(byteStream)
        ) {
            out.writeObject(cube);
            out.flush();
            serialized = byteStream.toByteArray();
        } catch (OutOfMemoryError oom) {
            throw new IOException("Concept too large to serialize: " + conceptPath, oom);
        }
        if (serialized.length > maxSerializedBytes) {
            throw new IOException(
                String.format(
                    "Concept %s too large for encryption: %d bytes serialized (limit %d). Lower maxObservationsPerConcept or split the concept.",
                    conceptPath, serialized.length, maxSerializedBytes
                )
            );
        }
        return Crypto.encryptData(encryptionKeyName, serialized);
    }

    /** Pre-sizes the serialization buffer to avoid repeated doubling copies of very large concepts. */
    private int estimateSerializationSize(PhenoCube<?> cube) {
        long observations = cube.sortedByKey().length;
        long estimate = (long) ((observations * 80L + observations * (cube.isStringType() ? 50L : 8L)) * 1.1 * 1.2);
        return (int) Math.max(4096, Math.min(estimate, Math.min(maxSerializedBytes, Integer.MAX_VALUE - 8)));
    }

    /**
     * Reads all partials of a concept into one cube, dropping exact duplicate observations (same patient, value and timestamp). Partials
     * written while the concept was still numeric are converted to text when the concept was promoted to categorical.
     */
    @SuppressWarnings("unchecked")
    private PhenoCube<?> mergePartials(String conceptPath, ConceptMetadata meta) throws IOException {
        List<Path> partials = spoolFiles.getOrDefault(conceptPath, Collections.emptyList());

        long totalObservations = meta.observationCount.get();
        if (totalObservations > maxMergedObservations) {
            throw new IOException(
                String.format(
                    "Concept %s has %d observations, more than the %d that fit in one Java array. Lower maxObservationsPerConcept.",
                    conceptPath, totalObservations, maxMergedObservations
                )
            );
        }

        PhenoCube<?> merged = newCube(conceptPath, meta);
        Set<ObservationFingerprint> seen = new HashSet<>();
        List<KeyAndValue> entries = new ArrayList<>();
        long read = 0;
        for (Path partial : partials) {
            try (
                InputStream fis = Files.newInputStream(partial); GZIPInputStream gzis = new GZIPInputStream(fis); ObjectInputStream ois =
                    new ObjectInputStream(gzis)
            ) {
                List<KeyAndValue> partialEntries = (List<KeyAndValue>) ois.readObject();
                read += partialEntries.size();
                for (KeyAndValue entry : partialEntries) {
                    if (meta.categorical && entry.getValue() instanceof Double) {
                        entry.setValue(entry.getValue().toString());
                    }
                    ObservationFingerprint fingerprint = new ObservationFingerprint(
                        entry.getKey(), conceptPath, entry.getValue().toString(), entry.getTimestamp() != null ? entry.getTimestamp() : 0L
                    );
                    if (seen.add(fingerprint)) {
                        entries.add(entry);
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to deserialize partial: " + partial, e);
            }
        }
        if (read != entries.size()) {
            log.info("Concept {}: {} observations read, {} duplicates dropped", conceptPath, read, read - entries.size());
        }
        ((PhenoCube) merged).setLoadingMap(entries);
        return merged;
    }

    /** Sorts by patient and, for categorical concepts, builds the value to patients map. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void complete(PhenoCube cube) {
        List<KeyAndValue> loadingMap = cube.getLoadingMap();
        KeyAndValue[] sorted = loadingMap.toArray(new KeyAndValue[0]);
        Arrays.sort(sorted, Comparator.comparingInt(KeyAndValue::getKey));
        cube.setSortedByKey(sorted);
        cube.setLoadingMap(null); // not serialized (transient); release the list while the array is written

        if (cube.isStringType()) {
            TreeMap<Comparable, TreeSet<Integer>> categoryMap = new TreeMap<>();
            for (KeyAndValue entry : (KeyAndValue[]) cube.sortedByValue()) {
                categoryMap.computeIfAbsent((Comparable) entry.getValue(), k -> new TreeSet<>()).add(entry.getKey());
            }
            cube.setCategoryMap(categoryMap);
        }
    }

    /** Column width of a categorical concept: the longest value in UTF-8 bytes (as the legacy CSV loader records it). */
    private int categoricalWidth(PhenoCube<?> cube) {
        int width = 1;
        for (KeyAndValue<?> entry : cube.sortedByKey()) {
            String text = (String) entry.getValue();
            if (text.length() * 3 > width) {
                width = Math.max(width, text.getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return width;
    }

    private void writeConceptRejectionReport() {
        Path reportPath = outputDirectory.resolve("concept_rejections.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map.Entry<String, AtomicLong> entry : new TreeMap<>(conceptRejections).entrySet()) {
                ConceptMetadata meta = conceptMetadata.get(entry.getKey());
                long accepted = meta != null ? meta.observationCount.get() : 0;
                long rejected = entry.getValue().get();
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("conceptPath", entry.getKey());
                record.put("observationsAccepted", accepted);
                record.put("observationsRejected", rejected);
                record.put("rejectionRate", rejected + accepted > 0 ? 100.0 * rejected / (accepted + rejected) : 0.0);
                record.put("reason", "exceeded_max_observations_per_concept");
                writer.write(mapper.writeValueAsString(record));
                writer.newLine();
            }
            log.info("Concept rejection report written: {} ({} concepts with rejections)", reportPath, conceptRejections.size());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write concept rejection report " + reportPath, e);
        }
    }

    private void cleanupSpool() throws IOException {
        for (List<Path> files : spoolFiles.values()) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
        spoolFiles.clear();
        for (Path bucketDir : spoolBucketDirs) {
            for (Path dir = bucketDir; dir != null && !dir.equals(spoolDirectory); dir = dir.getParent()) {
                try {
                    Files.deleteIfExists(dir);
                } catch (DirectoryNotEmptyException e) {
                    break;
                }
            }
        }
        spoolBucketDirs.clear();
    }

    /** Every patient id that has at least one accepted observation. */
    public Set<Integer> getAllIds() {
        return Collections.unmodifiableSet(allIds);
    }

    /** Concepts left out of the store by {@link #saveStore()}, with the reason. Empty when everything was written. */
    public Map<String, String> getFailedConcepts() {
        return Collections.unmodifiableMap(failedConcepts);
    }

    public int getConceptCount() {
        return conceptMetadata.size();
    }

    /** Total observations rejected because their concept hit {@code maxObservationsPerConcept}. */
    public long getRejectedObservationCount() {
        return conceptRejections.values().stream().mapToLong(AtomicLong::get).sum();
    }
}
