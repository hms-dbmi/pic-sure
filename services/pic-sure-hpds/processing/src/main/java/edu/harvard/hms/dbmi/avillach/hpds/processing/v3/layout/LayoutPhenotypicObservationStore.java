package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * The phenotype store over the Rust HPDS layout ({@code pheno.index} and {@code pheno.blocks}), the counterpart of
 * {@code PhenotypicObservationStore} that answers with roaring bitmaps instead of boxed integer sets.
 *
 * <p>The index is read once at construction. Blocks are read with positioned reads, authenticated and decrypted with the store key and the
 * associated data the format binds ({@code store id || concept path || format version as u16 LE}), decompressed, and kept in a Guava cache
 * bounded by decoded bytes. A range filter is two binary searches on the presorted values; there is no per-query sort.
 *
 * <p>Every bitmap returned may be shared with the cache; callers must not modify it.
 */
@Component
@ConditionalOnProperty(name = StoreFormat.PROPERTY, havingValue = StoreFormat.LAYOUT)
public class LayoutPhenotypicObservationStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LayoutPhenotypicObservationStore.class);

    /** File name of the block file in a store directory. */
    public static final String BLOCKS_FILE_NAME = "pheno.blocks";

    private static final byte[] BLOCKS_MAGIC = "HPDSBLK1".getBytes(StandardCharsets.US_ASCII);

    private static final int BLOCKS_HEADER_LENGTH = 24;

    private final LayoutIndex index;

    private final LayoutCipher cipher;

    private final FileChannel blocks;

    private final long blocksLength;

    private final Cache<String, LayoutBlock> cache;

    private final byte[] storeId;

    /**
     * Opens the store the service is configured for.
     *
     * @param dataDirectory {@code HPDS_DATA_DIRECTORY}, the directory holding {@code pheno.index} and {@code pheno.blocks}
     * @param keyFile {@code HPDS_ENCRYPTION_KEY_FILE}, the key file, by default the one {@code Crypto.loadDefaultKey} reads
     * @param cacheBytes {@code HPDS_CACHE_BYTES}, the bound on decoded block bytes held in the cache, 4 GiB by default as in the Rust
     *        service
     * @throws LayoutFormatException when the index or the block file cannot be opened or does not belong to this key
     */
    @Autowired
    public LayoutPhenotypicObservationStore(
        @Value("${HPDS_DATA_DIRECTORY:/opt/local/hpds/}") String dataDirectory,
        @Value("${HPDS_ENCRYPTION_KEY_FILE:/opt/local/hpds/encryption_key}") String keyFile,
        @Value("${HPDS_CACHE_BYTES:4294967296}") long cacheBytes
    ) {
        this(Path.of(dataDirectory), Path.of(keyFile), cacheBytes);
    }

    /**
     * Opens a store.
     *
     * @param dataDirectory the directory holding {@code pheno.index} and {@code pheno.blocks}
     * @param keyFile the key file
     * @param cacheBytes the bound on decoded block bytes held in the cache
     * @throws LayoutFormatException when the index or the block file cannot be opened or does not belong to this key
     */
    public LayoutPhenotypicObservationStore(Path dataDirectory, Path keyFile, long cacheBytes) {
        this.cipher = LayoutCipher.fromKeyFile(keyFile);
        this.index = LayoutIndex.read(dataDirectory.resolve(LayoutIndex.INDEX_FILE_NAME), cipher);
        this.storeId = index.storeId();
        Path blocksFile = dataDirectory.resolve(BLOCKS_FILE_NAME);
        try {
            this.blocks = FileChannel.open(blocksFile, StandardOpenOption.READ);
            this.blocksLength = blocks.size();
            byte[] header = readFully(0, BLOCKS_HEADER_LENGTH);
            if (!Arrays.equals(header, 0, BLOCKS_MAGIC.length, BLOCKS_MAGIC, 0, BLOCKS_MAGIC.length)) {
                throw new LayoutFormatException(blocksFile + " has a bad magic");
            }
            if (!Arrays.equals(header, BLOCKS_MAGIC.length, BLOCKS_HEADER_LENGTH, storeId, 0, storeId.length)) {
                throw new LayoutFormatException(blocksFile + " belongs to a different store than its index");
            }
        } catch (IOException e) {
            throw new LayoutFormatException("cannot open " + blocksFile, e);
        }
        this.cache = CacheBuilder.newBuilder().maximumWeight(cacheBytes)
            .weigher((String path, LayoutBlock block) -> (int) Math.min(block.heapBytes(), Integer.MAX_VALUE)).recordStats().build();
        log.info(
            "Opened layout store {}: {} concepts, {} patients, cache bound {} bytes", dataDirectory, index.concepts().size(),
            index.patients().getLongCardinality(), cacheBytes
        );
    }

    /** @return the decoded index */
    public LayoutIndex index() {
        return index;
    }

    /**
     * @param path a concept path
     * @return the concept's metadata, or empty for an unknown path
     */
    public Optional<LayoutConcept> concept(String path) {
        return Optional.ofNullable(index.get(path));
    }

    /**
     * The decoded block of a concept, from the cache or loaded into it.
     *
     * @param path a concept path
     * @return the block, or empty for an unknown path
     * @throws LayoutFormatException when the block cannot be read, authenticated or decoded
     */
    public Optional<LayoutBlock> getBlock(String path) {
        LayoutConcept concept = index.get(path);
        if (concept == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(cache.get(path, () -> load(concept)));
        } catch (ExecutionException | UncheckedExecutionException | ExecutionError e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LayoutFormatException("cannot load the block of " + path, e.getCause());
        }
    }

    /**
     * Patients with at least one value in {@code [min, max]} for a numeric concept, both bounds inclusive in {@code Double.compare} order
     * and a null bound open. An unknown path, or {@code min} above {@code max}, gives the empty set.
     *
     * @throws IllegalArgumentException when the concept is categorical
     */
    public RoaringBitmap getKeysForRange(String path, Double min, Double max) {
        return getBlock(path).map(block -> switch (block) {
            case NumericLayoutBlock numeric -> numeric.patientsInRange(min, max);
            case CategoricalLayoutBlock categorical -> throw new IllegalArgumentException("Range filter on categorical concept " + path);
        }).orElseGet(RoaringBitmap::new);
    }

    /**
     * Patients holding at least one of {@code values} for a categorical concept, by exact match. An unknown path or empty {@code values}
     * gives the empty set.
     *
     * @throws IllegalArgumentException when the concept is numeric and {@code values} is not empty
     */
    public RoaringBitmap getKeysForValues(String path, Collection<String> values) {
        if (values.isEmpty()) {
            return new RoaringBitmap();
        }
        return getBlock(path).map(block -> switch (block) {
            case CategoricalLayoutBlock categorical -> categorical.patientsWithValues(values);
            case NumericLayoutBlock numeric -> throw new IllegalArgumentException("Value filter on numeric concept " + path);
        }).orElseGet(RoaringBitmap::new);
    }

    /**
     * Every patient with at least one observation of a concept, empty for an unknown path.
     */
    public RoaringBitmap getAllKeys(String path) {
        return getBlock(path).map(LayoutBlock::allPatients).orElseGet(RoaringBitmap::new);
    }

    /**
     * Concepts whose path starts with {@code prefix}, the concept at {@code prefix} included.
     */
    public List<LayoutConcept> childConcepts(String prefix) {
        return index.withPrefix(prefix);
    }

    /** @return every patient in the store; shared, callers must not modify it */
    public RoaringBitmap getPatientIds() {
        return index.patients();
    }

    private LayoutBlock load(LayoutConcept concept) {
        long offset = concept.blockOffset();
        long length = concept.blockLength();
        if (offset < BLOCKS_HEADER_LENGTH || length < 0 || offset + length > blocksLength || length > Integer.MAX_VALUE) {
            throw new LayoutFormatException("block of " + concept.path() + " lies outside pheno.blocks");
        }
        byte[] stored;
        try {
            stored = readFully(offset, (int) length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = cipher.open(stored, blockAssociatedData(concept.path()), "block of " + concept.path());
        long limit = concept.maxPayloadLength();
        byte[] payload;
        if (index.compressed()) {
            payload = LayoutIndex.decompress(body, limit, "block of " + concept.path());
        } else if (body.length > limit) {
            throw new LayoutFormatException("block of " + concept.path() + " is larger than its concept allows");
        } else {
            payload = body;
        }
        LayoutBlock block = LayoutBlock.decode(payload, concept.path());
        if ((block instanceof CategoricalLayoutBlock) != concept.categorical()) {
            throw new LayoutFormatException("block of " + concept.path() + " disagrees with the index on the concept type");
        }
        return block;
    }

    private byte[] blockAssociatedData(String path) {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(storeId.length + pathBytes.length + 2).order(ByteOrder.LITTLE_ENDIAN).put(storeId).put(pathBytes)
            .putShort((short) LayoutIndex.FORMAT_VERSION).array();
    }

    private byte[] readFully(long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = blocks.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new LayoutFormatException("pheno.blocks is truncated");
            }
        }
        return buffer.array();
    }

    /**
     * @return hit, miss, load and eviction counts of the block cache since the store opened
     */
    public CacheStats cacheStats() {
        return cache.stats();
    }

    /** Logs the block cache statistics, then closes the block file. Spring calls it at shutdown. */
    @Override
    public void close() throws IOException {
        CacheStats stats = cache.stats();
        log.info(
            "Layout block cache at close: {} hits, {} misses, hit rate {}, {} loads, {} ms loading, {} evictions, {} blocks held",
            stats.hitCount(), stats.missCount(), String.format("%.4f", stats.hitRate()), stats.loadCount(),
            stats.totalLoadTime() / 1_000_000, stats.evictionCount(), cache.size()
        );
        blocks.close();
    }
}
