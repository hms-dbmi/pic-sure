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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * HPDS phenotype store writer with spool-and-finalize semantics.
 *
 * <p>The legacy {@code LoadingStore} needs its input sorted by concept: a concept that is evicted from its 16-entry cache and then seen
 * again would be written a second time and overwrite its own metadata. This store instead: <ul> <li>accepts observations for any concept in
 * any order, keeping at most {@code cacheSize} concepts in memory;</li> <li>spools an evicted concept's raw observations to a compressed
 * partial file on disk (several partials per concept are fine);</li> <li>at {@link #saveStore()} merges all partials of each concept
 * (optionally dropping exact duplicates), sorts, and writes every concept exactly once to {@code allObservationsStore.javabin}
 * (Java-serialized {@link PhenoCube}, AES-GCM encrypted with {@code encryptionKeyName}), plus {@code columnMeta.javabin} /
 * {@code columnMeta.csv} (the legacy 11 column format the dictionary ETL reads).</li> </ul>
 *
 * <p><b>Null sentinels and concept types.</b> A null sentinel ("NA", "None", ...) never types a concept, and what happens to it does not
 * depend on the order of the rows: sentinels are held next to the real values and settled when the store is saved, by the <i>final</i> type
 * of the concept. A concept whose real values are all numbers drops its sentinels (they are not values of a numeric concept); a concept
 * with any real text keeps them as categories; a concept with no real value at all is written as a categorical concept of its sentinels, as
 * {@code CSVLoader} does (so a consent whose patients all answered {@code NA} keeps them). Kept sentinels count against a non-zero
 * {@code maxObservationsPerConcept}. Numbers in a concept with real text are stored as the text they were written as ({@code 007} stays
 * {@code 007}, see {@link NumericText}), in any order. The one input the store cannot make order independent is a caller that passes
 * numeric-looking {@link String}s: the first real value decides whether such text is a number or a category (the CSV producer never does;
 * it turns numeric-looking text into numbers first).
 *
 * <p><b>Memory.</b> Finalization merges and serializes whole concepts. A heap budget (half of the max heap by default, about
 * {@value #BYTES_PER_OBSERVATION} bytes per observation) gates how many run at once, so memory stays bounded however many threads are
 * configured; a concept bigger than the budget runs alone.
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
    /** Failed attempts after which an add takes the eviction gate exclusively. */
    private static final int EXCLUSIVE_AFTER_ATTEMPTS = 4;

    /** Rough heap needed to merge, sort, serialize and encrypt one observation of a concept (list entry, boxed values, byte copies). */
    static final long BYTES_PER_OBSERVATION = 400;
    private static final double DEFAULT_HEAP_BUDGET_FRACTION = 0.5;

    /** What happened to an observation passed to {@link #addObservation}. */
    public enum AddResult {
        /** Added to the concept. */
        ADDED,
        /** The concept already holds {@code maxObservationsPerConcept} observations; counted in the rejection report. */
        REJECTED_CONCEPT_LIMIT,
        /**
         * A null-sentinel string ("NA", "None", ...). Held; it is stored if the concept turns out categorical and dropped if it turns out
         * numeric or never gets a real value. The final counts are in {@link #getStats()}.
         */
        HELD_NULL_SENTINEL
    }

    /**
     * Counts of what happened to the observations passed to {@link #addObservation}. Final after {@link #saveStore()}: held sentinels are
     * resolved then.
     *
     * @param accepted observations put into a concept (before duplicate removal)
     * @param skippedNullSentinel null sentinels dropped
     * @param rejectedConceptLimit observations refused because their concept reached {@code maxObservationsPerConcept}
     * @param duplicatesDropped exact duplicates removed at finalization (only with dedup)
     */
    public record StoreStats(long accepted, long skippedNullSentinel, long rejectedConceptLimit, long duplicatesDropped) {
    }

    private final Path spoolDirectory;
    private final Path outputDirectory;
    private final String encryptionKeyName;
    private final int cacheSize;
    private final long maxObservationsPerConcept;
    private final boolean dedup;
    private final int finalizationConcurrency;
    private final int finalizationChunkSize;
    private final long maxMergedObservations;
    private final long maxSerializedBytes;
    private final Semaphore heapBudgetKb;
    private final long heapBudgetTotalKb;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong skippedNullSentinel = new AtomicLong();
    private final AtomicLong duplicatesDropped = new AtomicLong();
    private final AtomicLong failedConceptObservations = new AtomicLong();
    private final AtomicLong spooledPartials = new AtomicLong();
    private volatile long observationsWritten;
    private volatile boolean aborted;

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
    private final ReentrantReadWriteLock evictionGate = new ReentrantReadWriteLock();
    private final AtomicReference<Throwable> spoolFailure = new AtomicReference<>();
    /** Concepts that could not be finalized (concept path to reason). Valid after {@link #saveStore()}. */
    private final Map<String, String> failedConcepts = new ConcurrentHashMap<>();

    private static final class ConceptMetadata {
        volatile boolean categorical;
        /** False until the concept has seen a real (non-sentinel) value. */
        volatile boolean typed;
        /** Null sentinels held for this concept; settled in {@link #saveStore()}. */
        volatile long sentinelCount;
        /** The patients of kept sentinels are added to the patient set when the concept is finalized. */
        volatile boolean addIdsAtFinalize;
        /** How many of the held sentinels the limit leaves room for; set when the sentinels are settled. */
        volatile long sentinelsToKeep;
        final AtomicInteger partialCount = new AtomicInteger();
        /** Real values accepted (the cap applies to these); after {@link #saveStore()} settles the sentinels, kept ones are included. */
        final AtomicLong observationCount = new AtomicLong();

        ConceptMetadata(boolean categorical, boolean typed) {
            this.categorical = categorical;
            this.typed = typed;
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

    /** Keeps duplicates, no concept limit semantics change: see the full constructor. */
    public SpoolingLoadingStore(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize
    ) {
        this(
            spoolDirectory, outputDirectory, encryptionKeyName, cacheSize, maxObservationsPerConcept, finalizationConcurrency,
            finalizationChunkSize, false
        );
    }

    /**
     * @param maxObservationsPerConcept observations accepted per concept, 0 = unlimited
     * @param dedup drop exact duplicate observations (same patient, value and timestamp) when a concept is finalized
     */
    public SpoolingLoadingStore(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize, boolean dedup
    ) {
        this(
            spoolDirectory, outputDirectory, encryptionKeyName, cacheSize, maxObservationsPerConcept, finalizationConcurrency,
            finalizationChunkSize, dedup, DEFAULT_MAX_MERGED_OBSERVATIONS, DEFAULT_MAX_SERIALIZED_BYTES,
            (long) (Runtime.getRuntime().maxMemory() * DEFAULT_HEAP_BUDGET_FRACTION)
        );
    }

    /** Package-private so tests can exercise the 2 GB guards and the heap gate with small limits. */
    SpoolingLoadingStore(
        Path spoolDirectory, Path outputDirectory, String encryptionKeyName, int cacheSize, int maxObservationsPerConcept,
        int finalizationConcurrency, int finalizationChunkSize, boolean dedup, long maxMergedObservations, long maxSerializedBytes,
        long heapBudgetBytes
    ) {
        if (cacheSize < 1 || maxObservationsPerConcept < 0 || finalizationConcurrency < 1 || finalizationChunkSize < 1) {
            throw new IllegalArgumentException(
                "cacheSize, finalizationConcurrency and finalizationChunkSize must be >= 1 and maxObservationsPerConcept >= 0"
            );
        }
        if (!Crypto.hasKey(encryptionKeyName)) {
            throw new IllegalStateException("No encryption key loaded under the name '" + encryptionKeyName + "'");
        }
        this.spoolDirectory = spoolDirectory;
        this.outputDirectory = outputDirectory;
        this.encryptionKeyName = encryptionKeyName;
        this.maxObservationsPerConcept = maxObservationsPerConcept == 0 ? Long.MAX_VALUE : maxObservationsPerConcept;
        this.cacheSize = cacheSize;
        this.dedup = dedup;
        this.finalizationConcurrency = finalizationConcurrency;
        this.finalizationChunkSize = finalizationChunkSize;
        this.maxMergedObservations = maxMergedObservations;
        this.maxSerializedBytes = maxSerializedBytes;
        this.heapBudgetTotalKb = Math.max(1, Math.min(Integer.MAX_VALUE, heapBudgetBytes / 1024));
        this.heapBudgetKb = new Semaphore((int) heapBudgetTotalKb);

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
                        if (!aborted && active.cube.getLoadingMap() != null && !active.cube.getLoadingMap().isEmpty()) {
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
     * Adds an observation to a concept. A concept's type (numeric or categorical) is fixed by its first real (non-sentinel) observation. A
     * text value arriving for a numeric concept is parsed as a number when possible and otherwise promotes the whole concept to
     * categorical. A number arriving for a categorical concept is stored as text. See the class comment for how null sentinels are held and
     * settled.
     *
     * @param value a {@link String}, or a number: a {@link Double}, a {@link Long} or a {@link NumericText} (see there)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public AddResult addObservation(int patientNum, String conceptPath, Comparable<?> value, Date timestamp) {
        if (!NumericText.isNumber(value) && !(value instanceof String)) {
            throw new IllegalArgumentException("Observation value must be a number or a String, got: " + value);
        }
        checkSpoolHealth();
        boolean sentinel = value instanceof String text && nullSentinelDetector.isNullSentinel(text);

        ReentrantLock lock = conceptLocks.computeIfAbsent(conceptPath, k -> new ReentrantLock());
        lock.lock();
        try {
            ConceptMetadata meta = conceptMetadata.get(conceptPath);
            if (meta == null) {
                meta = new ConceptMetadata(value instanceof String && !sentinel, !sentinel);
                conceptMetadata.put(conceptPath, meta);
            }

            Comparable<?> coerced = value;
            if (!sentinel) {
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
                if (!meta.typed) {
                    meta.categorical = value instanceof String;
                    meta.typed = true;
                } else if (meta.categorical && NumericText.isNumber(value)) {
                    coerced = NumericText.textOf(value);
                } else if (!meta.categorical && value instanceof String text) {
                    try {
                        coerced = Double.parseDouble(text);
                    } catch (NumberFormatException e) {
                        // Real text in a numeric concept: it is really categorical. Flip it in place (never replace the metadata: its
                        // partial counter and observation count must survive). Numbers already stored are converted to text when merged.
                        log.warn(
                            "Concept '{}' was numeric but received non-numeric text '{}': promoting to categorical", conceptPath, text
                        );
                        meta.categorical = true;
                        coerced = text;
                    }
                }
            }

            for (int attempt = 0; attempt < MAX_CACHE_RETRIES; attempt++) {
                // A cube can be evicted between cache.get() and the monitor below, and cache.get() itself spools the cubes it evicts, so
                // with a small cache and many threads a thread can lose that race again and again. After a few losses it takes the gate
                // exclusively: nobody else touches the cache, so its cube cannot be evicted before it is used.
                Lock gate = attempt < EXCLUSIVE_AFTER_ATTEMPTS ? evictionGate.readLock() : evictionGate.writeLock();
                gate.lock();
                try {
                    ActiveCube active = getActive(conceptPath);
                    synchronized (active) {
                        if (!active.spooled) {
                            // The loading list holds whatever was added; the type of the cube is only a label until the concept is merged
                            ((PhenoCube) active.cube).add(patientNum, coerced, timestamp);
                            if (sentinel) {
                                meta.sentinelCount++;
                                return AddResult.HELD_NULL_SENTINEL;
                            }
                            meta.observationCount.incrementAndGet();
                            accepted.incrementAndGet();
                            allIds.add(patientNum);
                            return AddResult.ADDED;
                        }
                    }
                } finally {
                    gate.unlock();
                }
                // Evicted by another thread between get() and the monitor: fetch (and re-create) the cube.
            }
            throw new IllegalStateException("Could not obtain an active cube for concept: " + conceptPath);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Settles the held null sentinels by the final type of each concept. Concepts without a real value become categorical concepts of their
     * sentinels. Kept sentinels count against the observation limit. Single-threaded, after the last add.
     */
    private void settleSentinels() {
        for (Map.Entry<String, ConceptMetadata> entry : conceptMetadata.entrySet()) {
            ConceptMetadata meta = entry.getValue();
            long held = meta.sentinelCount;
            if (!meta.typed) {
                meta.typed = true;
                meta.categorical = true;
                log.info("Concept {} holds only null sentinels ({}); written as a categorical concept", entry.getKey(), held);
            } else if (!meta.categorical) {
                skippedNullSentinel.addAndGet(held); // dropped when the concept is merged
                continue;
            }
            if (held > 0) {
                long room = Math.max(0, maxObservationsPerConcept - meta.observationCount.get());
                long keep = Math.min(held, room);
                long refused = held - keep;
                if (refused > 0) {
                    conceptRejections.computeIfAbsent(entry.getKey(), k -> new AtomicLong()).addAndGet(refused);
                    log.warn(
                        "Concept '{}' reached observation limit ({}) with its null sentinels: {} of them are refused", entry.getKey(),
                        maxObservationsPerConcept, refused
                    );
                }
                accepted.addAndGet(keep);
                meta.observationCount.addAndGet(keep);
                meta.sentinelsToKeep = keep;
                meta.addIdsAtFinalize = keep > 0;
            }
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
            spooledPartials.incrementAndGet();
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
     * The caller must treat a store with failed concepts, or with {@code rejectedConceptLimit > 0} in {@link #getStats()}, as incomplete.
     * The files are written in the order store, columnMeta.csv, columnMeta.javabin, so an interrupted write lacks the last one.
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

        settleSentinels();

        ConcurrentHashMap<String, ColumnMeta> metadataMap = new ConcurrentHashMap<>();
        List<String> allConcepts = new ArrayList<>(conceptMetadata.keySet());
        int totalConcepts = allConcepts.size();
        int totalChunks = (totalConcepts + finalizationChunkSize - 1) / finalizationChunkSize;
        long finalizationStart = System.currentTimeMillis();
        log.info(
            "Finalizing {} concepts in {} chunks of up to {} ({} concurrent, heap budget {} MB)", totalConcepts, totalChunks,
            finalizationChunkSize, finalizationConcurrency, heapBudgetTotalKb / 1024
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
        observationsWritten = sortedMetadata.values().stream().mapToLong(ColumnMeta::getObservationCount).sum();
        LegacyColumnMetaCsv.write(sortedMetadata, outputDirectory.resolve("columnMeta.csv"));
        try (
            ObjectOutputStream metaOut =
                new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(outputDirectory.resolve("columnMeta.javabin"))))
        ) {
            metaOut.writeObject(sortedMetadata);
            synchronized (allIds) {
                metaOut.writeObject(new TreeSet<>(allIds));
            }
        }

        cleanupSpool();
        writeConceptRejectionReport();
        log.info(
            "Store finalized: {} concepts, {} patients, {} partial files were spooled (a large number means the cache of {} concepts is too small)",
            metadataMap.size(), allIds.size(), spooledPartials.get(), cacheSize
        );
    }

    /** Discards what was collected without writing a store, and removes the spool files. Safe after {@link #saveStore()} too. */
    public void abort() {
        aborted = true;
        cache.invalidateAll();
        cache.cleanUp();
        try {
            cleanupSpool();
        } catch (IOException e) {
            log.warn("Could not remove all spool files of an aborted store: {}", e.getMessage());
        }
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
                        failedConceptObservations.addAndGet(conceptMetadata.get(conceptPath).observationCount.get());
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
        int permits = (int) Math.max(1, Math.min(heapBudgetTotalKb, meta.observationCount.get() * BYTES_PER_OBSERVATION / 1024 + 1));
        try {
            heapBudgetKb.acquire(permits);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for heap budget to finalize " + conceptPath, e);
        }
        try {
            return finalizeConceptWithBudget(conceptPath, meta, store, start);
        } finally {
            heapBudgetKb.release(permits);
        }
    }

    private ColumnMeta finalizeConceptWithBudget(String conceptPath, ConceptMetadata meta, RandomAccessFile store, long start)
        throws IOException {
        PhenoCube<?> cube = mergePartials(conceptPath, meta);
        if (cube.getLoadingMap().isEmpty()) {
            log.warn("Concept {} has no observations; not written", conceptPath);
            return null;
        }
        if (meta.addIdsAtFinalize) {
            for (KeyAndValue<?> entry : cube.getLoadingMap()) {
                allIds.add(entry.getKey());
            }
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
     * Reads all partials of a concept into one cube, dropping exact duplicate observations (same patient, value and timestamp) when
     * {@code dedup} is on. Partials written while the concept was still numeric are converted to text when the concept was promoted to
     * categorical; text sentinels left in the partials of a concept that turned out numeric are dropped (already counted as skipped).
     */
    @SuppressWarnings("unchecked")
    private PhenoCube<?> mergePartials(String conceptPath, ConceptMetadata meta) throws IOException {
        List<Path> partials = spoolFiles.getOrDefault(conceptPath, Collections.emptyList());

        long totalObservations = meta.observationCount.get();
        if (totalObservations > maxMergedObservations) {
            throw new IOException(
                String.format(
                    "Concept %s has %d observations, more than the %d that fit in one Java array. Split the concept or its partition.",
                    conceptPath, totalObservations, maxMergedObservations
                )
            );
        }

        PhenoCube<?> merged = newCube(conceptPath, meta);
        Set<ObservationFingerprint> seen = dedup ? new HashSet<>() : null;
        List<KeyAndValue> entries = new ArrayList<>((int) Math.min(totalObservations, 10_000_000L));
        long read = 0;
        long keptSentinels = 0;
        for (Path partial : partials) {
            try (
                InputStream fis = Files.newInputStream(partial); GZIPInputStream gzis = new GZIPInputStream(fis); ObjectInputStream ois =
                    new ObjectInputStream(gzis)
            ) {
                List<KeyAndValue> partialEntries = (List<KeyAndValue>) ois.readObject();
                for (KeyAndValue entry : partialEntries) {
                    Object stored = entry.getValue();
                    if (meta.categorical) {
                        if (NumericText.isNumber(stored)) {
                            entry.setValue(NumericText.textOf(stored)); // the text it was written as
                        } else if (nullSentinelDetector.isNullSentinel(stored)) {
                            if (keptSentinels++ >= meta.sentinelsToKeep) {
                                continue; // the limit leaves no room for it (already counted as refused)
                            }
                        }
                    } else if (stored instanceof String text) {
                        if (nullSentinelDetector.isNullSentinel(text)) {
                            continue;
                        }
                        entry.setValue(Double.parseDouble(text));
                    } else if (!(stored instanceof Double)) {
                        entry.setValue(NumericText.doubleOf(stored));
                    }
                    read++;
                    if (seen != null) {
                        ObservationFingerprint fingerprint = new ObservationFingerprint(
                            entry.getKey(), conceptPath, entry.getValue().toString(),
                            entry.getTimestamp() != null ? entry.getTimestamp() : 0L
                        );
                        if (!seen.add(fingerprint)) {
                            continue;
                        }
                    }
                    entries.add(entry);
                }
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to deserialize partial: " + partial, e);
            }
        }
        if (read != entries.size()) {
            duplicatesDropped.addAndGet(read - entries.size());
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

    /** One line per concept that is missing observations or entirely: over the limit, or failed at finalization. */
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
            for (Map.Entry<String, String> entry : new TreeMap<>(failedConcepts).entrySet()) {
                ConceptMetadata meta = conceptMetadata.get(entry.getKey());
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("conceptPath", entry.getKey());
                record.put("observationsAccepted", meta != null ? meta.observationCount.get() : 0);
                record.put("observationsRejected", meta != null ? meta.observationCount.get() : 0);
                record.put("rejectionRate", 100.0);
                record.put("reason", "finalization_failed: " + entry.getValue());
                writer.write(mapper.writeValueAsString(record));
                writer.newLine();
            }
            log.info(
                "Concept rejection report written: {} ({} concepts over the limit, {} failed)", reportPath, conceptRejections.size(),
                failedConcepts.size()
            );
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

    /** Concepts that have a type: not counting those that saw only null sentinels. */
    public int getConceptCount() {
        return (int) conceptMetadata.values().stream().filter(meta -> meta.typed).count();
    }

    /** Total observations rejected because their concept hit {@code maxObservationsPerConcept}. */
    public long getRejectedObservationCount() {
        return conceptRejections.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /** Concepts that hit {@code maxObservationsPerConcept}, with the number of observations rejected. */
    public Map<String, Long> getRejectedConcepts() {
        Map<String, Long> rejected = new TreeMap<>();
        conceptRejections.forEach((concept, count) -> rejected.put(concept, count.get()));
        return rejected;
    }

    /** Partial files written to the spool so far: how often concepts were evicted from the in-memory cache. */
    public long getSpooledPartials() {
        return spooledPartials.get();
    }

    /** Observations in the concepts that were written. Valid after {@link #saveStore()}. */
    public long getObservationsWritten() {
        return observationsWritten;
    }

    /** Observations that had been accepted into concepts that could not be written. Valid after {@link #saveStore()}. */
    public long getFailedConceptObservations() {
        return failedConceptObservations.get();
    }

    /** What happened to the observations passed in; final after {@link #saveStore()}. */
    public StoreStats getStats() {
        return new StoreStats(accepted.get(), skippedNullSentinel.get(), getRejectedObservationCount(), duplicatesDropped.get());
    }
}
