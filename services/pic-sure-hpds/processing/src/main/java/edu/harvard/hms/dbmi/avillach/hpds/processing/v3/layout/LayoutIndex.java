package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import com.github.luben.zstd.Zstd;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The decoded {@code pheno.index} of a layout store: header, every patient id, and the concept table sorted by path in Java
 * {@code String.compareTo} order. The byte layout is documented in the {@code hpds-format} crate.
 *
 * <p>Only encrypted stores are read. A plain store is refused, matching the Rust reader's rule that a reader holding a key never accepts a
 * plain store.
 */
public final class LayoutIndex {

    /** File name of the index in a store directory. */
    public static final String INDEX_FILE_NAME = "pheno.index";

    /** Format version this reader understands, bound into every block's associated data. */
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = "HPDSPHN1".getBytes(StandardCharsets.US_ASCII);

    private static final int STORE_ID_LENGTH = 16;

    private static final int FLAG_ENCRYPTED = 1;

    private static final int FLAG_COMPRESSED = 1 << 1;

    private static final int META_HAS_TIMESTAMP = 1;

    private static final int META_MIN = 1 << 1;

    private static final int META_MAX = 1 << 2;

    private static final int META_TIMESTAMP_MIN = 1 << 3;

    private static final int META_TIMESTAMP_MAX = 1 << 4;

    private static final int META_CATEGORY_VALUES = 1 << 5;

    private static final long MAX_BODY_LENGTH = Integer.MAX_VALUE - 8;

    private final byte[] storeId;

    private final boolean compressed;

    private final RoaringBitmap patients;

    private final List<LayoutConcept> concepts;

    private final String[] paths;

    private final Map<String, LayoutConcept> byPath;

    private LayoutIndex(byte[] storeId, boolean compressed, RoaringBitmap patients, List<LayoutConcept> concepts) {
        this.storeId = storeId;
        this.compressed = compressed;
        this.patients = patients;
        this.concepts = Collections.unmodifiableList(concepts);
        this.paths = concepts.stream().map(LayoutConcept::path).toArray(String[]::new);
        this.byPath = new HashMap<>(concepts.size() * 2);
        for (LayoutConcept concept : concepts) {
            byPath.put(concept.path(), concept);
        }
    }

    /**
     * Reads and decodes {@code pheno.index}.
     *
     * @param indexFile the index file
     * @param cipher the store key
     * @return the decoded index
     * @throws LayoutFormatException when the file cannot be read, is not an encrypted layout index of a supported version, fails
     *         authentication, or is corrupt
     */
    public static LayoutIndex read(Path indexFile, LayoutCipher cipher) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(indexFile);
        } catch (IOException e) {
            throw new LayoutFormatException("cannot read " + indexFile, e);
        }
        return decode(bytes, cipher);
    }

    /**
     * Decodes the bytes of a {@code pheno.index}.
     *
     * @param bytes the whole file
     * @param cipher the store key
     * @return the decoded index
     * @throws LayoutFormatException as for {@link #read}
     */
    public static LayoutIndex decode(byte[] bytes, LayoutCipher cipher) {
        LittleEndianReader reader = new LittleEndianReader(bytes, "index header");
        if (!Arrays.equals(reader.bytes(MAGIC.length), MAGIC)) {
            throw new LayoutFormatException("pheno.index has a bad magic");
        }
        int version = reader.u16();
        if (version != FORMAT_VERSION) {
            throw new LayoutFormatException("pheno.index format version " + version + " is not supported");
        }
        int flags = reader.u16();
        if ((flags & ~(FLAG_ENCRYPTED | FLAG_COMPRESSED)) != 0) {
            throw new LayoutFormatException("pheno.index has unknown flags " + flags);
        }
        if ((flags & FLAG_ENCRYPTED) == 0) {
            throw new LayoutFormatException("pheno.index is a plain store; this reader requires an encrypted one");
        }
        byte[] storeId = reader.bytes(STORE_ID_LENGTH);
        reader.string();
        reader.i64();
        reader.string();
        int preambleLength = reader.position();
        long storedLength = reader.i64();
        byte[] stored = reader.bytes(storedLength);
        reader.finish();
        byte[] body = cipher.open(stored, Arrays.copyOf(bytes, preambleLength), "pheno.index");
        boolean compressed = (flags & FLAG_COMPRESSED) != 0;
        if (compressed) {
            body = decompress(body, MAX_BODY_LENGTH, "pheno.index body");
        }
        LittleEndianReader bodyReader = new LittleEndianReader(body, "index body");
        long patientsLength = bodyReader.i64();
        int patientsStart = bodyReader.skip(patientsLength);
        RoaringBitmap patients = deserializeBitmap(body, patientsStart, (int) patientsLength, "patient bitmap");
        long count = bodyReader.u32();
        List<LayoutConcept> concepts = new ArrayList<>((int) Math.min(count, body.length / 64));
        for (long i = 0; i < count; i++) {
            concepts.add(readConcept(bodyReader));
        }
        bodyReader.finish();
        for (int i = 1; i < concepts.size(); i++) {
            if (concepts.get(i - 1).path().compareTo(concepts.get(i).path()) >= 0) {
                throw new LayoutFormatException("pheno.index concepts are not strictly sorted by path");
            }
        }
        return new LayoutIndex(storeId, compressed, patients, concepts);
    }

    private static LayoutConcept readConcept(LittleEndianReader reader) {
        String path = reader.string();
        int type = reader.u8();
        if (type > 1) {
            throw new LayoutFormatException("concept " + path + " has unknown type " + type);
        }
        int flags = reader.u8();
        if ((flags & ~((1 << 6) - 1)) != 0) {
            throw new LayoutFormatException("concept " + path + " has unknown metadata flags " + flags);
        }
        long blockOffset = reader.i64();
        long blockLength = reader.i64();
        long observationCount = reader.u32();
        long patientCount = reader.u32();
        int widthInBytes = reader.i32();
        int columnOffset = reader.i32();
        double min = reader.f64();
        double max = reader.f64();
        long timestampMin = reader.i64();
        long timestampMax = reader.i64();
        long valueCount = reader.u32();
        List<String> values = new ArrayList<>((int) Math.min(valueCount, reader.remaining() / 4));
        for (long i = 0; i < valueCount; i++) {
            values.add(reader.string());
        }
        return new LayoutConcept(
            path, type == 1, blockOffset, blockLength, observationCount, patientCount, (flags & META_HAS_TIMESTAMP) != 0,
            (flags & META_MIN) != 0 ? min : null, (flags & META_MAX) != 0 ? max : null,
            (flags & META_TIMESTAMP_MIN) != 0 ? timestampMin : null, (flags & META_TIMESTAMP_MAX) != 0 ? timestampMax : null, widthInBytes,
            columnOffset, (flags & META_CATEGORY_VALUES) != 0 ? Collections.unmodifiableList(values) : null
        );
    }

    /**
     * Decompresses one zstd frame whose header records its content size.
     *
     * @param frame the compressed bytes
     * @param limit the largest content size accepted
     * @param what a name for the frame, used in error messages
     * @return the decompressed bytes
     * @throws LayoutFormatException when the frame is unreadable, does not record its size, declares more than {@code limit} bytes, or
     *         decodes to a different length than declared
     */
    static byte[] decompress(byte[] frame, long limit, String what) {
        long size = Zstd.getFrameContentSize(frame);
        if (size < 0) {
            throw new LayoutFormatException(what + " is not a zstd frame with a recorded content size");
        }
        if (size > limit || size > MAX_BODY_LENGTH) {
            throw new LayoutFormatException(what + " declares " + size + " decoded bytes, above the limit of " + limit);
        }
        byte[] out = new byte[(int) size];
        long written = Zstd.decompress(out, frame);
        if (Zstd.isError(written) || written != size) {
            throw new LayoutFormatException(what + " failed to decompress");
        }
        return out;
    }

    /**
     * Deserializes a roaring bitmap in the portable format from a slice of {@code bytes}.
     *
     * @throws LayoutFormatException when the slice is not exactly one serialized bitmap
     */
    static RoaringBitmap deserializeBitmap(byte[] bytes, int offset, int length, String what) {
        RoaringBitmap bitmap = new RoaringBitmap();
        try {
            bitmap.deserialize(ByteBuffer.wrap(bytes, offset, length).slice());
        } catch (IOException | RuntimeException e) {
            throw new LayoutFormatException(what + " is corrupt", e);
        }
        if (bitmap.serializedSizeInBytes() != length) {
            throw new LayoutFormatException(what + " has trailing bytes");
        }
        return bitmap;
    }

    /** @return the store id bound into every block's associated data */
    public byte[] storeId() {
        return storeId.clone();
    }

    /** @return true when blocks are zstd-compressed */
    public boolean compressed() {
        return compressed;
    }

    /** @return every patient in the store; shared, callers must not modify it */
    public RoaringBitmap patients() {
        return patients;
    }

    /** @return every concept, sorted by path */
    public List<LayoutConcept> concepts() {
        return concepts;
    }

    /**
     * @param path a concept path
     * @return the concept at exactly that path, or null
     */
    public LayoutConcept get(String path) {
        return byPath.get(path);
    }

    /**
     * Every concept whose path starts with {@code prefix}, the concept at {@code prefix} included: the concepts
     * {@code PhenotypeMetaStore.getChildConceptPaths} returns.
     *
     * @param prefix a path prefix
     * @return the matching concepts in path order
     */
    public List<LayoutConcept> withPrefix(String prefix) {
        int start = Arrays.binarySearch(paths, prefix);
        if (start < 0) {
            start = -start - 1;
        }
        int end = start;
        while (end < paths.length && paths[end].startsWith(prefix)) {
            end++;
        }
        return concepts.subList(start, end);
    }

    /**
     * Builds the dictionary the javabin store's {@code columnMeta.javabin} would hold, keyed by concept path.
     *
     * @return a new map in path order
     */
    public TreeMap<String, ColumnMeta> toColumnMetaMap() {
        TreeMap<String, ColumnMeta> metaStore = new TreeMap<>();
        for (LayoutConcept concept : concepts) {
            metaStore.put(concept.path(), concept.toColumnMeta());
        }
        return metaStore;
    }
}
