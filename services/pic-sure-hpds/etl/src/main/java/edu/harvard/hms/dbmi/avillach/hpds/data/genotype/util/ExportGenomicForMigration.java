package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.BucketIndexBySample;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.CompressedIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.FileBackedByteIndexedInfoStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMask;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskSparseImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMetadataIndex;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util.ExportForMigration;
import edu.harvard.hms.dbmi.avillach.hpds.data.storage.FileBackedStorageVariantMasksImpl;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

// spotless:off
/**
 * Exports a Java genomic store, every node exactly as the query service reads it, for {@code hpds-etl genomic-migrate}.
 *
 * <p> Reads the genomic root in one of the four layouts of {@code hpds.genomicProcessor.impl} with Java's own classes and writes one
 * encrypted file per node plus a manifest to the export directory. The layouts: {@code local} (the root is the node),
 * {@code localDistributed} (each directory in the root is a chromosome node of one group), {@code localPatientDistributed} (each directory
 * in the root is a patient group, each directory in a group a chromosome node) and {@code localPatientOnlyDistributed} (each directory in
 * the root is a group and its node). Every directory counts, as {@code File.listFiles(File::isDirectory)} lists it; a node directory without
 * {@code variantStore.javabin} is refused. Groups and nodes are exported in Java {@code String.compareTo} order of their directory names.
 *
 * <p> Nothing is written to the genomic root, which may be mounted read only: storage files are read through their recorded offsets, never
 * opened for writing as the Java classes open them. The {@code variantIndex_fbbis*} files the query service writes at startup are ignored;
 * the spec index comes from {@code variantSpecIndex.javabin}. A store whose mask storage is not {@link FileBackedStorageVariantMasksImpl}
 * (the legacy {@code VariantMasks} format) is refused by class name.
 *
 * <p> Every file is encrypted with the phenotype store's {@code DEFAULT} key in the container {@link ExportForMigration} writes, under one
 * random export id, with kind byte {@link #MANIFEST_KIND} for {@link #MANIFEST_FILE} and {@link #NODE_KIND} for each node file. The export
 * directory is created mode {@code 0700} and the files mode {@code 0600}; it may be neither the genomic root, inside it, nor around it.
 * Files a previous export left behind are deleted first. Node files are renamed into place as each completes and the manifest last, so a
 * reader that finds the manifest finds a complete export.
 *
 * <p> Content, big-endian, strings as in {@link ExportForMigration}: an {@code int} UTF-8 byte length, then the bytes. A string holding an
 * unpaired surrogate, which UTF-8 cannot represent, is refused with its location. A position set is a tag byte, then for tag 0 an
 * {@code int} count and that many ascending {@code int} positions, or for tag 1 an {@code int} byte length of {@code ceil(P / 8)} and a
 * bitmap in which position {@code p} is bit {@code p % 8} of byte {@code p / 8}; {@code P} is the node's patient count and every position is
 * below it.
 *
 * <pre>
 * manifest:
 *   mode            string   the layout name
 *   node_count      int, then per node in export order:
 *     group         string   the group directory name; empty for local and localDistributed
 *     dir           string   the chromosome directory name; empty for local and localPatientOnlyDistributed
 *     file          string   the node file's name
 *     column_count  int, then that many column names, ascending
 *
 * node file:
 *   group, dir      string   as in the manifest
 *   patient_count   int, then that many strings: VariantStore.patientIds in stored order
 *   spec_count      int      length of the spec index
 *   bucket_index    byte     1 when BucketIndexBySample.javabin exists, else 0
 *   info_count      int, then per info store, ascending by column:
 *     column        string   the file name less "_infoStore.javabin", which queries name
 *     description   string
 *     continuous    byte
 *     key_count     int, then per key, ascending: key string, int id count, that many spec index ids
 *     ranges        int      -1 when categorical, else the CompressedIndex ranges, each:
 *       lower_kind  byte     0 unbounded, 1 open, 2 closed
 *       lower       int      Float.floatToRawIntBits of the endpoint, 0 when unbounded
 *       upper_kind  byte
 *       upper       int
 *       entries     int, then per entry in TreeMap order: int float bits, int key count, keys
 *   then per bucket holding anything, ascending by contig, then bucket:
 *     tag           byte     1
 *     contig        string
 *     bucket        int
 *     masks         int, then per mask record, ascending by spec: spec, then he, ho, hen, hon, each a form byte (0 absent,
 *                  1 VariantMaskSparseImpl, 2 VariantMaskBitmaskImpl) followed, unless absent, by a position set
 *     metadata      int, then per spec, ascending: spec, int string count (-1 for a null array), the strings
 *     specs         int, then per spec index entry placed in the bucket, ascending by id: int id, spec
 *     carriers      byte     0 the bucket index has no bit for the bucket, 1 a position set follows (the patients whose bucket
 *                  index mask has the bucket's bit), 2 the node has no bucket index
 *   end             byte 0, then long buckets, long mask records, long metadata entries, long patients missing from the bucket index
 * </pre>
 *
 * <p> A spec belongs to the bucket {@code Integer.parseInt(spec.split(",")[1]) / 1000} of contig {@code spec.split(",")[0]}, as the query
 * service looks it up. A spec index entry that has no such bucket, and a mask or metadata record stored under another bucket than its
 * spec's, are refused.
 *
 * <p> Usage: {@code ExportGenomicForMigration <mode> <genomic root> <key file> <export directory>}.
 */
// spotless:on
public class ExportGenomicForMigration {

    /** The manifest, renamed into place last. */
    public static final String MANIFEST_FILE = "genomic.bin";

    /** The {@code kind} byte of manifest frames. */
    public static final byte MANIFEST_KIND = 3;

    /** The {@code kind} byte of node file frames. */
    public static final byte NODE_KIND = 4;

    /** The layouts, as {@code hpds.genomicProcessor.impl} names them. */
    public static final List<String> MODES = List.of("local", "localDistributed", "localPatientDistributed", "localPatientOnlyDistributed");

    /** Most groups Java's {@code GenomicProcessorConfig} accepts. */
    public static final int MAX_GROUPS = 10;

    /** Most chromosome nodes per group Java's {@code GenomicProcessorConfig} accepts. */
    public static final int MAX_NODES_PER_GROUP = 50;

    static final byte FORM_ABSENT = 0;

    static final byte FORM_SPARSE = 1;

    static final byte FORM_BITMASK = 2;

    static final byte SET_LIST = 0;

    static final byte SET_BITMAP = 1;

    static final byte CARRIERS_NOT_INDEXED = 0;

    static final byte CARRIERS_PRESENT = 1;

    static final byte CARRIERS_NO_INDEX = 2;

    private static final String NODE_FILE_FORMAT = "node-%05d.bin";

    private static final String INFO_STORE_SUFFIX = "infoStore.javabin";

    private static final String BUCKET_INDEX_FILE = "BucketIndexBySample.javabin";

    private static final String TEMPORARY_SUFFIX = ".tmp";

    private static final int BUCKET_SIZE = VariantStore.BUCKET_SIZE;

    private final String mode;

    private final Path root;

    private final Path keyFile;

    private final Path exportDirectory;

    /**
     * @param mode the layout, one of {@link #MODES}
     * @param root the genomic root, Java's {@code HPDS_GENOMIC_DATA_DIRECTORY}
     * @param keyFile the phenotype store's key file, read with {@code Crypto.loadKey}
     * @param exportDirectory directory to write the export to; created mode {@code 0700} if missing
     */
    public ExportGenomicForMigration(String mode, Path root, Path keyFile, Path exportDirectory) {
        this.mode = mode;
        this.root = root;
        this.keyFile = keyFile;
        this.exportDirectory = exportDirectory;
    }

    /**
     * Runs the export from the command line.
     *
     * @param args the layout, the genomic root, the key file and the export directory
     * @throws Exception when the store cannot be read or the export cannot be written
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: ExportGenomicForMigration <mode> <genomic root> <key file> <export directory>");
            System.exit(2);
        }
        Summary summary = new ExportGenomicForMigration(args[0], Path.of(args[1]), Path.of(args[2]), Path.of(args[3])).export();
        System.out.println(
            "Exported " + summary.nodes() + " nodes, " + summary.buckets() + " buckets and " + summary.maskRecords() + " mask records to "
                + args[3]
        );
    }

    /**
     * Counts of what one export wrote.
     *
     * @param nodes node files written
     * @param buckets bucket records written
     * @param maskRecords mask records written
     */
    public record Summary(long nodes, long buckets, long maskRecords) {
    }

    /** One node of the layout: its group and chromosome directory names, and its directory. */
    record Node(String group, String dir, Path path) {
    }

    /**
     * Loads the key, finds the nodes and writes the encrypted export.
     *
     * @return what was written
     * @throws IOException when a file cannot be read or written
     * @throws ClassNotFoundException when the javabins hold a class this build does not have
     * @throws IllegalStateException when the mode is unknown, the key cannot be loaded, the export directory overlaps the root, the layout
     *         breaks Java's limits, or the store holds something the export cannot represent exactly; the message names the node and item
     */
    public Summary export() throws IOException, ClassNotFoundException {
        if (!MODES.contains(mode)) {
            throw new IllegalStateException("unknown mode " + mode + "; expected one of " + MODES);
        }
        Path store = root.toRealPath();
        Path export = ExportForMigration.realPathAllowingMissing(exportDirectory);
        if (export.startsWith(store) || store.startsWith(export)) {
            throw new IllegalStateException(
                "the export directory " + export + " must be outside the genomic root " + store + " and must not contain it"
            );
        }
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, keyFile.toString());
        if (!Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
            throw new IllegalStateException("no encryption key could be loaded from " + keyFile);
        }
        List<Node> nodes = layout(mode, store);

        ExportForMigration.createPrivateDirectory(export);
        try (Stream<Path> stale = Files.list(export)) {
            for (Path path : (Iterable<Path>) stale::iterator) {
                String name = path.getFileName().toString();
                if (name.startsWith(MANIFEST_FILE) || (name.startsWith("node-") && (name.endsWith(".bin") || name.endsWith(TEMPORARY_SUFFIX)))) {
                    Files.delete(path);
                }
            }
        }
        byte[] exportId = new byte[ExportForMigration.EXPORT_ID_BYTES];
        new SecureRandom().nextBytes(exportId);

        long buckets = 0;
        long maskRecords = 0;
        List<String> fileNames = new ArrayList<>();
        List<List<String>> columns = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            String name = String.format(NODE_FILE_FORMAT, i);
            Path temporary = export.resolve(name + TEMPORARY_SUFFIX);
            try {
                try (DataOutputStream out = open(temporary, exportId, NODE_KIND)) {
                    NodeExporter exporter = new NodeExporter(node, out);
                    exporter.write();
                    buckets += exporter.buckets;
                    maskRecords += exporter.maskRecords;
                    columns.add(exporter.columns);
                }
                Files.move(temporary, export.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            fileNames.add(name);
        }
        Path manifestTemporary = export.resolve(MANIFEST_FILE + TEMPORARY_SUFFIX);
        try {
            try (DataOutputStream out = open(manifestTemporary, exportId, MANIFEST_KIND)) {
                writeString(out, mode, "the mode");
                out.writeInt(nodes.size());
                for (int i = 0; i < nodes.size(); i++) {
                    Node node = nodes.get(i);
                    writeString(out, node.group(), node.path().toString());
                    writeString(out, node.dir(), node.path().toString());
                    writeString(out, fileNames.get(i), node.path().toString());
                    out.writeInt(columns.get(i).size());
                    for (String column : columns.get(i)) {
                        writeString(out, column, node.path() + " column name");
                    }
                }
            }
            Files.move(manifestTemporary, export.resolve(MANIFEST_FILE), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(manifestTemporary);
        }
        return new Summary(nodes.size(), buckets, maskRecords);
    }

    private static DataOutputStream open(Path file, byte[] exportId, byte kind) throws IOException {
        return new DataOutputStream(
            new BufferedOutputStream(
                new ExportForMigration.EncryptingOutputStream(ExportForMigration.createPrivateFile(file), exportId, kind), 1 << 20
            )
        );
    }

    /**
     * The nodes of {@code root} in {@code mode}, ordered by group, then directory name, in Java {@code String.compareTo} order.
     *
     * @throws IllegalStateException when a level holds more directories than Java accepts, or a node directory has no
     *         {@code variantStore.javabin}
     */
    static List<Node> layout(String mode, Path root) {
        List<Node> nodes = new ArrayList<>();
        switch (mode) {
            case "local" -> nodes.add(new Node("", "", root));
            case "localDistributed" -> {
                for (Path dir : directories(root, MAX_NODES_PER_GROUP, "chromosome")) {
                    nodes.add(new Node("", dir.getFileName().toString(), dir));
                }
            }
            case "localPatientDistributed" -> {
                for (Path group : directories(root, MAX_GROUPS, "patient group")) {
                    for (Path dir : directories(group, MAX_NODES_PER_GROUP, "chromosome")) {
                        nodes.add(new Node(group.getFileName().toString(), dir.getFileName().toString(), dir));
                    }
                }
            }
            default -> {
                for (Path group : directories(root, MAX_GROUPS, "patient group")) {
                    nodes.add(new Node(group.getFileName().toString(), "", group));
                }
            }
        }
        for (Node node : nodes) {
            if (!Files.isRegularFile(node.path().resolve(VariantStore.VARIANT_STORE_JAVABIN_FILENAME))) {
                throw new IllegalStateException(
                    node.path() + " has no " + VariantStore.VARIANT_STORE_JAVABIN_FILENAME + "; it is not a genomic node, and the " + mode
                        + " layout takes every directory at its level as one"
                );
            }
        }
        return nodes;
    }

    private static List<Path> directories(Path parent, int limit, String what) {
        File[] found = parent.toFile().listFiles(File::isDirectory);
        if (found == null) {
            throw new IllegalStateException(parent + " cannot be listed");
        }
        if (found.length > limit) {
            throw new IllegalStateException(parent + " holds " + found.length + " " + what + " directories; Java accepts at most " + limit);
        }
        List<Path> dirs = new ArrayList<>();
        for (File file : found) {
            dirs.add(file.toPath());
        }
        dirs.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return dirs;
    }

    /** Writes one node file. Holds the node's spec index, its bucket index masks and one bucket at a time. */
    static final class NodeExporter {

        private final Node node;

        private final DataOutputStream out;

        final List<String> columns = new ArrayList<>();

        long buckets;

        long maskRecords;

        private long metadataEntries;

        private long missingFromBucketIndex;

        NodeExporter(Node node, DataOutputStream out) {
            this.node = node;
            this.out = out;
        }

        private String where(String item) {
            return node.path() + ": " + item;
        }

        void write() throws IOException, ClassNotFoundException {
            File dir = node.path().toFile();
            VariantStore store = readVariantStore(dir);
            String[] patients = store.getPatientIds();
            if (patients == null) {
                throw new IllegalStateException(where("VariantStore.patientIds is null"));
            }
            String[] specIndex = VariantStore.loadVariantIndexFromFile(dir.getPath());
            Map<String, TreeMap<Integer, List<Integer>>> specsByBucket = placeSpecIndex(specIndex);

            writeString(out, node.group(), where("group name"));
            writeString(out, node.dir(), where("directory name"));
            out.writeInt(patients.length);
            for (int p = 0; p < patients.length; p++) {
                if (patients[p] == null) {
                    throw new IllegalStateException(where("patient " + p + " is null"));
                }
                writeString(out, patients[p], where("patient " + p));
            }
            out.writeInt(specIndex.length);

            File bucketIndexFile = new File(dir, BUCKET_INDEX_FILE);
            BucketCarriers carriers = bucketIndexFile.isFile() ? new BucketCarriers(readObject(bucketIndexFile), dir, patients) : null;
            out.writeByte(carriers == null ? 0 : 1);

            writeInfoStores(dir, specIndex.length);

            Map<String, ReadOnlyStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> masks = maskStorages(store, dir);
            Map<String, ReadOnlyStorage<Integer, ConcurrentHashMap<String, String[]>>> metadata = metadataStorages(dir);
            try {
                TreeSet<String> contigs = new TreeSet<>(masks.keySet());
                contigs.addAll(metadata.keySet());
                contigs.addAll(specsByBucket.keySet());
                for (String contig : contigs) {
                    ReadOnlyStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> maskStorage = masks.get(contig);
                    ReadOnlyStorage<Integer, ConcurrentHashMap<String, String[]>> metadataStorage = metadata.get(contig);
                    TreeMap<Integer, List<Integer>> specBuckets = specsByBucket.getOrDefault(contig, new TreeMap<>());
                    TreeSet<Integer> bucketKeys = new TreeSet<>(specBuckets.keySet());
                    if (maskStorage != null) {
                        bucketKeys.addAll(maskStorage.keys());
                    }
                    if (metadataStorage != null) {
                        bucketKeys.addAll(metadataStorage.keys());
                    }
                    for (int bucket : bucketKeys) {
                        ConcurrentHashMap<String, VariableVariantMasks> bucketMasks = maskStorage == null ? null : maskStorage.get(bucket);
                        ConcurrentHashMap<String, String[]> bucketMetadata = metadataStorage == null ? null : metadataStorage.get(bucket);
                        List<Integer> ids = specBuckets.getOrDefault(bucket, List.of());
                        boolean empty = (bucketMasks == null || bucketMasks.isEmpty()) && (bucketMetadata == null || bucketMetadata.isEmpty())
                            && ids.isEmpty();
                        if (!empty) {
                            writeBucket(contig, bucket, bucketMasks, bucketMetadata, ids, specIndex, patients.length, carriers);
                        }
                    }
                }
            } finally {
                for (ReadOnlyStorage<?, ?> storage : masks.values()) {
                    storage.close();
                }
                for (ReadOnlyStorage<?, ?> storage : metadata.values()) {
                    storage.close();
                }
            }
            out.writeByte(0);
            out.writeLong(buckets);
            out.writeLong(maskRecords);
            out.writeLong(metadataEntries);
            out.writeLong(missingFromBucketIndex);
        }

        private VariantStore readVariantStore(File dir) throws IOException, ClassNotFoundException {
            Object read;
            try {
                read = readObject(new File(dir, VariantStore.VARIANT_STORE_JAVABIN_FILENAME));
            } catch (InvalidClassException e) {
                throw new IllegalStateException(
                    where(
                        VariantStore.VARIANT_STORE_JAVABIN_FILENAME + " holds a class this build cannot read (" + e.getMessage()
                            + "); stores in the legacy VariantMasks format must be reloaded with the current ETL"
                    ),
                    e
                );
            }
            if (!(read instanceof VariantStore store)) {
                throw new IllegalStateException(
                    where(VariantStore.VARIANT_STORE_JAVABIN_FILENAME + " holds a " + className(read) + ", not a VariantStore")
                );
            }
            for (Map.Entry<String, ?> entry : store.getVariantMaskStorage().entrySet()) {
                Object storage = entry.getValue();
                if (storage != null && storage.getClass() != FileBackedStorageVariantMasksImpl.class) {
                    throw new IllegalStateException(
                        where(
                            "contig " + entry.getKey() + " stores its masks in " + className(storage) + ", not "
                                + FileBackedStorageVariantMasksImpl.class.getName()
                                + ": the legacy VariantMasks format, which this export does not read; reload the store with the current ETL"
                        )
                    );
                }
            }
            return store;
        }

        /** Groups the spec index ids by the contig and bucket the query service looks each spec up in. */
        private Map<String, TreeMap<Integer, List<Integer>>> placeSpecIndex(String[] specIndex) {
            Map<String, TreeMap<Integer, List<Integer>>> placed = new HashMap<>();
            for (int id = 0; id < specIndex.length; id++) {
                String spec = specIndex[id];
                if (spec == null) {
                    throw new IllegalStateException(where("spec index entry " + id + " is null"));
                }
                Placement placement = place(spec);
                if (placement == null) {
                    throw new IllegalStateException(
                        where("spec index entry " + id + " (" + spec + ") has no contig and integer position, so no bucket can hold it")
                    );
                }
                placed.computeIfAbsent(placement.contig(), c -> new TreeMap<>()).computeIfAbsent(placement.bucket(), b -> new ArrayList<>())
                    .add(id);
            }
            return placed;
        }

        private void writeInfoStores(File dir, int specCount) throws IOException, ClassNotFoundException {
            String[] files = dir.list((d, name) -> name.endsWith(INFO_STORE_SUFFIX));
            TreeMap<String, String> byColumn = new TreeMap<>();
            for (String file : files == null ? new String[0] : files) {
                String column = file.replace("_infoStore.javabin", "");
                String previous = byColumn.put(column, file);
                if (previous != null) {
                    throw new IllegalStateException(where(previous + " and " + file + " both name the column " + column));
                }
            }
            out.writeInt(byColumn.size());
            for (Map.Entry<String, String> entry : byColumn.entrySet()) {
                String column = entry.getKey();
                columns.add(column);
                Object read = readObject(new File(dir, entry.getValue()));
                if (!(read instanceof FileBackedByteIndexedInfoStore info)) {
                    throw new IllegalStateException(where(entry.getValue() + " holds a " + className(read)));
                }
                writeInfoStore(column, info, dir, specCount);
            }
        }

        private void writeInfoStore(String column, FileBackedByteIndexedInfoStore info, File dir, int specCount) throws IOException {
            String what = "info column " + column;
            if (!column.equals(info.column_key)) {
                throw new IllegalStateException(
                    where(what + ": column_key " + info.column_key + " differs from the file name, which queries use; listings use column_key")
                );
            }
            if (info.description == null) {
                throw new IllegalStateException(where(what + ": the description is null"));
            }
            if (!Float.valueOf(Float.MAX_VALUE).equals(info.min) || !Float.valueOf(Float.MIN_VALUE).equals(info.max)) {
                throw new IllegalStateException(
                    where(what + ": min " + info.min + " and max " + info.max + " are not the sentinels Java never replaces")
                );
            }
            writeString(out, column, where(what));
            writeString(out, info.description, where(what + " description"));
            out.writeBoolean(info.isContinuous);
            try (ReadOnlyStorage<String, Integer[]> values = new ReadOnlyStorage<>(info.getAllValues(), dir)) {
                List<String> keys = new ArrayList<>(values.keys());
                Collections.sort(keys);
                out.writeInt(keys.size());
                for (String key : keys) {
                    String keyWhere = where(what + " key " + key);
                    Integer[] ids = values.get(key);
                    if (ids == null) {
                        throw new IllegalStateException(keyWhere + ": no variant ids are stored");
                    }
                    writeString(out, key, keyWhere);
                    out.writeInt(ids.length);
                    for (Integer id : ids) {
                        if (id == null || id < 0 || id >= specCount) {
                            throw new IllegalStateException(keyWhere + ": variant id " + id + " is outside the spec index of " + specCount);
                        }
                        out.writeInt(id);
                    }
                }
            }
            if (!info.isContinuous) {
                out.writeInt(-1);
                return;
            }
            CompressedIndex index = info.continuousValueIndex;
            if (index == null) {
                out.writeInt(0);
                return;
            }
            Map<Range<Float>, byte[]> ranges = compressedRangeMap(index);
            out.writeInt(ranges.size());
            for (Map.Entry<Range<Float>, byte[]> range : ranges.entrySet()) {
                Range<Float> r = range.getKey();
                writeBound(r.hasLowerBound(), r.hasLowerBound() ? r.lowerBoundType() : null, r.hasLowerBound() ? r.lowerEndpoint() : null);
                writeBound(r.hasUpperBound(), r.hasUpperBound() ? r.upperBoundType() : null, r.hasUpperBound() ? r.upperEndpoint() : null);
                TreeMap<?, ?> map = rangeMap(range.getValue(), what);
                out.writeInt(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof Float value) || !(e.getValue() instanceof TreeSet<?> keys)) {
                        throw new IllegalStateException(where(what + ": a CompressedIndex map entry is not Float to TreeSet"));
                    }
                    out.writeInt(Float.floatToRawIntBits(value));
                    out.writeInt(keys.size());
                    for (Object key : keys) {
                        if (!(key instanceof String text)) {
                            throw new IllegalStateException(where(what + ": a CompressedIndex key is not a String"));
                        }
                        writeString(out, text, where(what + " CompressedIndex key"));
                    }
                }
            }
        }

        private void writeBound(boolean bounded, BoundType type, Float endpoint) throws IOException {
            if (!bounded) {
                out.writeByte(0);
                out.writeInt(0);
                return;
            }
            out.writeByte(type == BoundType.CLOSED ? 2 : 1);
            out.writeInt(Float.floatToRawIntBits(endpoint));
        }

        private TreeMap<?, ?> rangeMap(byte[] compressed, String what) throws IOException {
            try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
                Object map = in.readObject();
                if (!(map instanceof TreeMap<?, ?> tree)) {
                    throw new IllegalStateException(where(what + ": a CompressedIndex range holds a " + className(map)));
                }
                return tree;
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(where(what + ": a CompressedIndex range holds an unknown class"), e);
            }
        }

        private Map<String, ReadOnlyStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> maskStorages(VariantStore store, File dir)
            throws IOException {
            Map<String, ReadOnlyStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> storages = new HashMap<>();
            for (Map.Entry<String, ? extends FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> entry : store
                .getVariantMaskStorage().entrySet()) {
                if (entry.getValue() != null) {
                    storages.put(entry.getKey(), new ReadOnlyStorage<>(entry.getValue(), dir));
                }
            }
            return storages;
        }

        @SuppressWarnings("unchecked")
        private Map<String, ReadOnlyStorage<Integer, ConcurrentHashMap<String, String[]>>> metadataStorages(File dir) throws IOException {
            Map<String, ReadOnlyStorage<Integer, ConcurrentHashMap<String, String[]>>> storages = new HashMap<>();
            File file = new File(dir, VariantMetadataIndex.VARIANT_METADATA_FILENAME);
            if (!file.isFile()) {
                return storages;
            }
            Object read;
            try {
                read = readObject(file);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(where(file.getName() + " holds an unknown class"), e);
            }
            if (!(read instanceof VariantMetadataIndex index)) {
                throw new IllegalStateException(where(file.getName() + " holds a " + className(read)));
            }
            Map<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>> indexMap =
                (Map<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>>) field(VariantMetadataIndex.class, "indexMap", index);
            for (Map.Entry<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>> entry : indexMap.entrySet()) {
                if (entry.getValue() != null) {
                    storages.put(entry.getKey(), new ReadOnlyStorage<>(entry.getValue(), dir));
                }
            }
            return storages;
        }

        private void writeBucket(
            String contig, int bucket, ConcurrentHashMap<String, VariableVariantMasks> bucketMasks, ConcurrentHashMap<String, String[]> bucketMetadata, List<Integer> ids,
            String[] specIndex, int patientCount, BucketCarriers carriers
        ) throws IOException {
            String bucketWhere = "contig " + contig + " bucket " + bucket;
            out.writeByte(1);
            writeString(out, contig, where(bucketWhere));
            out.writeInt(bucket);
            List<String> maskSpecs = bucketMasks == null ? new ArrayList<>() : new ArrayList<>(bucketMasks.keySet());
            Collections.sort(maskSpecs);
            out.writeInt(maskSpecs.size());
            for (String spec : maskSpecs) {
                requirePlaced(spec, contig, bucket, "mask record");
                writeString(out, spec, where(bucketWhere + " mask record"));
                VariableVariantMasks record = bucketMasks.get(spec);
                String specWhere = where(bucketWhere + " spec " + spec);
                writeMask(record.heterozygousMask, patientCount, specWhere + " he");
                writeMask(record.homozygousMask, patientCount, specWhere + " ho");
                writeMask(record.heterozygousNoCallMask, patientCount, specWhere + " hen");
                writeMask(record.homozygousNoCallMask, patientCount, specWhere + " hon");
                maskRecords++;
            }
            List<String> metadataSpecs = bucketMetadata == null ? new ArrayList<>() : new ArrayList<>(bucketMetadata.keySet());
            Collections.sort(metadataSpecs);
            out.writeInt(metadataSpecs.size());
            for (String spec : metadataSpecs) {
                requirePlaced(spec, contig, bucket, "metadata entry");
                String specWhere = where(bucketWhere + " metadata of " + spec);
                writeString(out, spec, specWhere);
                String[] strings = bucketMetadata.get(spec);
                if (strings == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(strings.length);
                    for (String s : strings) {
                        if (s == null) {
                            throw new IllegalStateException(specWhere + ": a metadata string is null");
                        }
                        writeString(out, s, specWhere);
                    }
                }
                metadataEntries++;
            }
            out.writeInt(ids.size());
            for (int id : ids) {
                out.writeInt(id);
                writeString(out, specIndex[id], where("spec index entry " + id));
            }
            if (carriers == null) {
                out.writeByte(CARRIERS_NO_INDEX);
            } else {
                int[] positions = carriers.positions(contig, bucket);
                if (positions == null) {
                    out.writeByte(CARRIERS_NOT_INDEXED);
                } else {
                    out.writeByte(CARRIERS_PRESENT);
                    writeShorterSet(positions, patientCount);
                }
            }
            buckets++;
        }

        private void requirePlaced(String spec, String contig, int bucket, String what) {
            Placement placement = place(spec);
            if (placement == null || !placement.contig().equals(contig) || placement.bucket() != bucket) {
                throw new IllegalStateException(
                    where(
                        "the " + what + " of " + spec + " is stored under contig " + contig + " bucket " + bucket
                            + ", where the query service never looks it up"
                    )
                );
            }
        }

        private void writeMask(VariantMask mask, int patientCount, String maskWhere) throws IOException {
            if (mask == null) {
                out.writeByte(FORM_ABSENT);
            } else if (mask instanceof VariantMaskSparseImpl sparse) {
                Set<Integer> indexes = sparse.getPatientIndexes();
                if (indexes == null) {
                    throw new IllegalStateException(maskWhere + ": a sparse mask without indexes");
                }
                int[] positions = new int[indexes.size()];
                int i = 0;
                for (Integer index : indexes) {
                    if (index == null || index < 0 || index >= patientCount) {
                        throw new IllegalStateException(maskWhere + ": patient index " + index + " outside 0 to " + (patientCount - 1));
                    }
                    positions[i++] = index;
                }
                Arrays.sort(positions);
                out.writeByte(FORM_SPARSE);
                writeList(positions);
            } else if (mask instanceof VariantMaskBitmaskImpl bitmask) {
                out.writeByte(FORM_BITMASK);
                writeBitmap(bitmaskPositions(bitmask.getBitmask(), patientCount, maskWhere), patientCount);
            } else {
                throw new IllegalStateException(maskWhere + ": a mask of class " + className(mask));
            }
        }

        private void writeShorterSet(int[] positions, int patientCount) throws IOException {
            long listBytes = 4L * positions.length;
            long bitmapBytes = (patientCount + 7L) / 8;
            if (listBytes <= bitmapBytes) {
                writeList(positions);
            } else {
                byte[] bitmap = new byte[(int) bitmapBytes];
                for (int p : positions) {
                    bitmap[p >>> 3] |= (byte) (1 << (p & 7));
                }
                writeBitmap(bitmap, patientCount);
            }
        }

        private void writeList(int[] positions) throws IOException {
            out.writeByte(SET_LIST);
            out.writeInt(positions.length);
            for (int p : positions) {
                out.writeInt(p);
            }
        }

        private void writeBitmap(byte[] bitmap, int patientCount) throws IOException {
            out.writeByte(SET_BITMAP);
            out.writeInt(bitmap.length);
            out.write(bitmap);
        }
    }

    /**
     * The patients of a Java bitmask as a bitmap of {@code ceil(P / 8)} bytes, position {@code p} at bit {@code p % 8} of byte
     * {@code p / 8}. Java pads the bitmask with two set bits at each end, so patient position {@code p} is bit {@code p + 2}.
     *
     * @throws IllegalStateException when the bitmask is not {@code P + 4} bits long or lacks its padding
     */
    static byte[] bitmaskPositions(BigInteger mask, int patientCount, String maskWhere) {
        if (mask == null || mask.signum() <= 0 || mask.bitLength() != patientCount + 4 || !mask.testBit(0) || !mask.testBit(1)
            || !mask.testBit(patientCount + 2)) {
            throw new IllegalStateException(
                maskWhere + ": a bitmask of " + (mask == null ? 0 : mask.bitLength()) + " bits is not " + patientCount
                    + " patients between two set padding bits at each end"
            );
        }
        byte[] bigEndian = mask.shiftRight(2).clearBit(patientCount).clearBit(patientCount + 1).toByteArray();
        byte[] bitmap = new byte[(patientCount + 7) / 8];
        for (int i = 0; i < bitmap.length && i < bigEndian.length; i++) {
            bitmap[i] = bigEndian[bigEndian.length - 1 - i];
        }
        return bitmap;
    }

    /** A spec's contig and bucket, as the query service derives them. */
    record Placement(String contig, int bucket) {
    }

    /**
     * The contig and bucket the query service looks {@code spec} up in: {@code spec.split(",")[0]} and
     * {@code Integer.parseInt(spec.split(",")[1]) / 1000}, or {@code null} when the split has fewer than two fields or the position is not
     * an {@code int}.
     */
    static Placement place(String spec) {
        String[] segments = spec.split(",");
        if (segments.length < 2) {
            return null;
        }
        try {
            return new Placement(segments[0], Integer.parseInt(segments[1]) / BUCKET_SIZE);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Java's {@code BucketIndexBySample}: per patient a bitmask of the buckets holding a variant the patient carries as {@code he},
     * {@code hen} or {@code ho}. Holds every patient's bitmask for the node.
     */
    static final class BucketCarriers {

        private final List<String> bucketList;

        private final BigInteger[] masks;

        private final int missing;

        @SuppressWarnings("unchecked")
        BucketCarriers(Object read, File dir, String[] patients) throws IOException {
            if (!(read instanceof BucketIndexBySample index)) {
                throw new IllegalStateException(dir + ": " + BUCKET_INDEX_FILE + " holds a " + className(read));
            }
            bucketList = (List<String>) field(BucketIndexBySample.class, "bucketList", index);
            FileBackedByteIndexedStorage<Integer, BigInteger> storage =
                (FileBackedByteIndexedStorage<Integer, BigInteger>) field(BucketIndexBySample.class, "patientBucketMasks", index);
            masks = new BigInteger[patients.length];
            int absent = 0;
            try (ReadOnlyStorage<Integer, BigInteger> values = new ReadOnlyStorage<>(storage, dir)) {
                for (int p = 0; p < patients.length; p++) {
                    int id;
                    try {
                        id = Integer.parseInt(patients[p]);
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException(dir + ": patient " + p + " is not an integer, which the bucket index needs", e);
                    }
                    masks[p] = values.get(id);
                    if (masks[p] == null) {
                        absent++;
                    }
                }
            }
            missing = absent;
        }

        /** The patient positions whose bitmask has the bucket's bit, or {@code null} when the bucket index has no such bucket. */
        int[] positions(String contig, int bucket) {
            int index = Collections.binarySearch(bucketList, contig + ":" + bucket);
            if (index < 0) {
                return null;
            }
            int bit = bucketList.size() - 1 - index + 2;
            int[] found = new int[masks.length];
            int n = 0;
            for (int p = 0; p < masks.length; p++) {
                if (masks[p] != null && masks[p].testBit(bit)) {
                    found[n++] = p;
                }
            }
            return Arrays.copyOf(found, n);
        }
    }

    /**
     * Reads a {@link FileBackedByteIndexedStorage} without opening its data file for writing, as the Java classes do: each value is read
     * from the offset and length recorded in its index and decoded by the storage's own {@code readObject(byte[])}.
     */
    static final class ReadOnlyStorage<K, V extends Serializable> implements Closeable {

        private final FileBackedByteIndexedStorage<K, V> storage;

        private final Map<K, Long[]> index;

        private final RandomAccessFile file;

        private final Method decode;

        @SuppressWarnings("unchecked")
        ReadOnlyStorage(FileBackedByteIndexedStorage<K, V> storage, File dir) throws IOException {
            this.storage = storage;
            this.index = (Map<K, Long[]>) field(FileBackedByteIndexedStorage.class, "index", storage);
            File recorded = (File) field(FileBackedByteIndexedStorage.class, "storageFile", storage);
            this.file = new RandomAccessFile(new File(dir, recorded.getName()), "r");
            this.decode = findDecode(storage.getClass());
        }

        Set<K> keys() {
            return index.keySet();
        }

        @SuppressWarnings("unchecked")
        V get(K key) throws IOException {
            Long[] location = index.get(key);
            if (location == null || location[0] == null || location[1] == null || location[1].intValue() <= 0) {
                return null;
            }
            byte[] buffer = new byte[location[1].intValue()];
            file.seek(location[0]);
            file.readFully(buffer);
            try {
                return (V) decode.invoke(storage, (Object) buffer);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IOException(cause);
            }
        }

        private static Method findDecode(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                Method method;
                try {
                    method = c.getDeclaredMethod("readObject", byte[].class);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException e) {
                    method = null;
                }
            }
            throw new IllegalStateException(type.getName() + " has no readObject(byte[])");
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Range<Float>, byte[]> compressedRangeMap(CompressedIndex index) {
        return (Map<Range<Float>, byte[]>) field(CompressedIndex.class, "compressedRangeMap", index);
    }

    private static Object field(Class<?> type, String name, Object target) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + "." + name + " cannot be read", e);
        }
    }

    private static Object readObject(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            return in.readObject();
        }
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /**
     * Writes {@code value} as an {@code int} UTF-8 byte length and the bytes.
     *
     * @throws IllegalStateException when {@code value} holds an unpaired surrogate, naming {@code location}
     */
    static void writeString(DataOutputStream out, String value, String location) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalStateException(
                    location + ": a string holds an unpaired surrogate at index " + i + ", which UTF-8 cannot represent exactly"
                );
            }
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
