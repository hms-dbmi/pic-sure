package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
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
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedByteIndexedStorage;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Dumps a Java genomic store in the canonical text form {@code hpds-etl genomic-dump} writes for a Rust genomic store, reading it with
 * Java's own classes in the ETL image.
 *
 * <p>Usage: {@code DumpGenomicCanonical <mode> <genomic root> <out file> [rows|digest]}, the mode being a value of
 * {@code hpds.genomicProcessor.impl}. The store may be mounted read only: storage files are read at their recorded offsets, never opened
 * for writing, and the {@code variantIndex_fbbis*} caches are ignored.
 *
 * <p>The form is documented on {@code hpds_etl::genomic::dump}. This dumper derives it from Java's data as the query service reads it: a
 * node's variants are the union of the specs of its mask records, its metadata and its spec index ({@code variantSpecIndex.javabin}), each
 * placed in the bucket {@code Integer.parseInt(spec.split(",")[1]) / 1000} of contig {@code spec.split(",")[0]}; a key's specs are its spec
 * index ids looked up in the spec index; a bucket's carriers are the patients whose {@code BucketIndexBySample} mask has the bucket's bit,
 * none when the index has no such bucket, and, when the node has no bucket index, the union of {@code he}, {@code hen} and {@code ho} over
 * the bucket's masks, as the service builds the index when it is missing. A record stored under another bucket than its spec's, which the
 * service never finds, is written as an {@code X} line instead.
 *
 * <p>Both forms hold patient-level genotype data: the {@code P} line lists every patient id, and the digest form still hashes each bucket's
 * carrier set, which a reader holding those ids can brute-force when a bucket has a single carrier. The default is therefore the digest
 * form and {@code rows} must be asked for by name, but the output of either form is handled, stored and deleted like the store itself. It
 * is created owner-only (mode {@code 0600}) with the parent directory resolved by the operating system, and the dumper refuses an existing
 * path, a file name of {@code .} or {@code ..}, and any output inside the genomic root. The mode and root are checked before the output is
 * created, and the output is deleted when the dump fails. The dumper writes no patient id or genotype value to its logs.
 */
public class DumpGenomicCanonical {

    private static final HexFormat HEX = HexFormat.of();

    private static final Set<String> MODES = Set.of("local", "localDistributed", "localPatientDistributed", "localPatientOnlyDistributed");

    private final List<Storage<?, ?>> opened = new ArrayList<>();

    private Writer out;

    private boolean digest;

    /**
     * Runs the dump.
     *
     * @param args mode, genomic root, output file, and optionally {@code digest} (the default) or {@code rows}
     * @throws Exception when the arguments are invalid, the store cannot be read or the output cannot be written
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("usage: DumpGenomicCanonical <mode> <genomic root> <out file> [digest|rows]");
        }
        String form = args.length > 3 ? args[3] : "digest";
        if (!form.equals("digest") && !form.equals("rows")) {
            throw new IllegalArgumentException("the form must be digest or rows, not " + form);
        }
        if (!MODES.contains(args[0])) {
            throw new IllegalArgumentException("unknown mode " + args[0]);
        }
        Path root = Path.of(args[1]);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("the genomic root " + root + " is not a directory");
        }
        Path output = resolveOutput(Path.of(args[2]), root);
        DumpGenomicCanonical dumper = new DumpGenomicCanonical();
        dumper.digest = form.equals("digest");
        boolean complete = false;
        try (
            Writer writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(ExportForMigration.createPrivateFile(output)), StandardCharsets.UTF_8),
                1 << 20
            )
        ) {
            dumper.out = writer;
            dumper.dump(args[0], root.toFile());
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(output);
            }
        }
    }

    /**
     * Resolves the output path with the operating system and checks it: the parent directory must exist and is resolved through symbolic
     * links, the file name may not be {@code .} or {@code ..}, nothing may exist at the path (a dangling symbolic link counts), and the
     * path may not lie inside the genomic root.
     *
     * @param output the requested output file
     * @param root the genomic root being dumped
     * @return the resolved path to create, exactly
     * @throws IOException when the path fails any check
     */
    static Path resolveOutput(Path output, Path root) throws IOException {
        Path name = output.getFileName();
        if (name == null || name.toString().equals(".") || name.toString().equals("..")) {
            throw new IOException("refusing the output path " + output + ": it must name a file");
        }
        Path target = output.toAbsolutePath().getParent().toRealPath().resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to overwrite the existing output path " + target);
        }
        if (target.startsWith(root.toRealPath())) {
            throw new IOException("refusing to write the output inside the genomic root " + root);
        }
        return target;
    }

    /**
     * Creates the output file, which must not exist, owner-only (mode {@code 0600}) on a POSIX file system, and opens it for writing.
     *
     * @param output the file to create
     * @param root the genomic root being dumped, inside which no output may be written
     * @return a stream writing the new file
     * @throws IOException when the path fails {@link #resolveOutput(Path, Path)} or the file cannot be created
     */
    static OutputStream openPrivateOutput(Path output, Path root) throws IOException {
        return Channels.newOutputStream(ExportForMigration.createPrivateFile(resolveOutput(output, root)));
    }

    private void dump(String mode, File root) throws Exception {
        line("M\t" + mode);
        List<String[]> nodes = new ArrayList<>();
        switch (mode) {
            case "local" -> nodes.add(new String[] {"", "", root.getPath()});
            case "localDistributed" -> {
                for (File dir : dirs(root)) {
                    nodes.add(new String[] {"", dir.getName(), dir.getPath()});
                }
            }
            case "localPatientDistributed" -> {
                for (File group : dirs(root)) {
                    for (File dir : dirs(group)) {
                        nodes.add(new String[] {group.getName(), dir.getName(), dir.getPath()});
                    }
                }
            }
            case "localPatientOnlyDistributed" -> {
                for (File group : dirs(root)) {
                    nodes.add(new String[] {group.getName(), "", group.getPath()});
                }
            }
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
        for (String[] node : nodes) {
            try {
                dumpNode(node[0], node[1], new File(node[2]));
            } finally {
                closeOpened();
            }
        }
    }

    private <K, V extends Serializable> Storage<K, V> open(FileBackedByteIndexedStorage<K, V> storage, File dir) throws Exception {
        Storage<K, V> opening = new Storage<>(storage, dir);
        opened.add(opening);
        return opening;
    }

    private void closeOpened() throws IOException {
        IOException failure = null;
        for (Storage<?, ?> storage : opened) {
            try {
                storage.close();
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        opened.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static List<File> dirs(File parent) {
        File[] found = parent.listFiles(File::isDirectory);
        List<File> list = new ArrayList<>(Arrays.asList(found == null ? new File[0] : found));
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    @SuppressWarnings("unchecked")
    private void dumpNode(String group, String dirName, File dir) throws Exception {
        VariantStore store = (VariantStore) readObject(new File(dir, VariantStore.VARIANT_STORE_JAVABIN_FILENAME));
        String[] patients = store.getPatientIds();
        int[] ids = new int[patients.length];
        for (int p = 0; p < patients.length; p++) {
            try {
                ids[p] = Integer.parseInt(patients[p]);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(dir + ": patient " + p + " is not an integer");
            }
        }
        String[] specIndex = VariantStore.loadVariantIndexFromFile(dir.getPath());

        Map<String, TreeMap<Integer, List<String>>> indexed = new HashMap<>();
        for (String spec : specIndex) {
            String[] segments = spec.split(",");
            int bucket = Integer.parseInt(segments[1]) / VariantStore.BUCKET_SIZE;
            indexed.computeIfAbsent(segments[0], c -> new TreeMap<>()).computeIfAbsent(bucket, b -> new ArrayList<>()).add(spec);
        }
        Map<String, Storage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> masks = new HashMap<>();
        for (
            Map.Entry<String, ? extends FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>>> e : store
                .getVariantMaskStorage().entrySet()
        ) {
            if (e.getValue() != null) {
                masks.put(e.getKey(), open(e.getValue(), dir));
            }
        }
        Map<String, Storage<Integer, ConcurrentHashMap<String, String[]>>> metadata = new HashMap<>();
        File metadataFile = new File(dir, VariantMetadataIndex.VARIANT_METADATA_FILENAME);
        if (metadataFile.isFile()) {
            VariantMetadataIndex index = (VariantMetadataIndex) readObject(metadataFile);
            Map<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>> indexMap =
                (Map<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>>) field(
                    VariantMetadataIndex.class, "indexMap", index
                );
            for (Map.Entry<String, FileBackedByteIndexedStorage<Integer, ConcurrentHashMap<String, String[]>>> e : indexMap.entrySet()) {
                if (e.getValue() != null) {
                    metadata.put(e.getKey(), open(e.getValue(), dir));
                }
            }
        }
        List<String> bucketList = null;
        BigInteger[] bucketMasks = null;
        File bucketIndexFile = new File(dir, "BucketIndexBySample.javabin");
        if (bucketIndexFile.isFile()) {
            BucketIndexBySample bucketIndex = (BucketIndexBySample) readObject(bucketIndexFile);
            bucketList = (List<String>) field(BucketIndexBySample.class, "bucketList", bucketIndex);
            Storage<Integer, BigInteger> storage = open(
                (FileBackedByteIndexedStorage<Integer, BigInteger>) field(BucketIndexBySample.class, "patientBucketMasks", bucketIndex), dir
            );
            bucketMasks = new BigInteger[ids.length];
            for (int p = 0; p < ids.length; p++) {
                bucketMasks[p] = storage.get(ids[p]);
            }
        }

        TreeSet<String> contigs = new TreeSet<>(masks.keySet());
        contigs.addAll(metadata.keySet());
        contigs.addAll(indexed.keySet());
        List<String> bucketLines = new ArrayList<>();
        Set<String> allSpecs = new java.util.HashSet<>();
        for (String contig : contigs) {
            Storage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> maskStorage = masks.get(contig);
            Storage<Integer, ConcurrentHashMap<String, String[]>> metadataStorage = metadata.get(contig);
            TreeMap<Integer, List<String>> specBuckets = indexed.getOrDefault(contig, new TreeMap<>());
            TreeSet<Integer> buckets = new TreeSet<>(specBuckets.keySet());
            if (maskStorage != null) {
                buckets.addAll(maskStorage.keys());
            }
            if (metadataStorage != null) {
                buckets.addAll(metadataStorage.keys());
            }
            for (int bucket : buckets) {
                Map<String, VariableVariantMasks> bucketMasksMap = maskStorage == null ? null : maskStorage.get(bucket);
                Map<String, String[]> bucketMetadata = metadataStorage == null ? null : metadataStorage.get(bucket);
                TreeSet<String> specs = new TreeSet<>(specBuckets.getOrDefault(bucket, List.of()));
                for (Map<String, ?> source : Arrays.asList(bucketMasksMap, bucketMetadata)) {
                    if (source == null) {
                        continue;
                    }
                    for (String spec : source.keySet()) {
                        String[] segments = spec.split(",");
                        if (!segments[0].equals(contig) || Integer.parseInt(segments[1]) / VariantStore.BUCKET_SIZE != bucket) {
                            bucketLines.add("X\t" + escape(contig) + "\t" + bucket + "\t" + escape(spec));
                        } else {
                            specs.add(spec);
                        }
                    }
                }
                if (specs.isEmpty()) {
                    continue;
                }
                allSpecs.addAll(specs);
                List<String> lines = new ArrayList<>();
                lines.add("B\t" + escape(contig) + "\t" + bucket);
                boolean[] derived = new boolean[ids.length];
                for (String spec : specs) {
                    VariableVariantMasks record = bucketMasksMap == null ? null : bucketMasksMap.get(spec);
                    StringBuilder line = new StringBuilder("V\t").append(escape(spec)).append('\t').append(record == null ? 0 : 1);
                    VariantMask[] four = record == null ? new VariantMask[4]
                        : new VariantMask[] {record.heterozygousMask, record.homozygousMask, record.heterozygousNoCallMask,
                            record.homozygousNoCallMask};
                    for (int k = 0; k < 4; k++) {
                        line.append('\t');
                        VariantMask mask = four[k];
                        if (mask == null) {
                            line.append('-');
                            continue;
                        }
                        int[] positions = positions(mask, ids.length);
                        line.append(mask instanceof VariantMaskBitmaskImpl ? "b:" : "s:").append(joinIds(positions, ids));
                        if (k != 3) {
                            for (int p : positions) {
                                derived[p] = true;
                            }
                        }
                    }
                    String[] strings = bucketMetadata == null ? null : bucketMetadata.get(spec);
                    int n = strings == null ? 0 : strings.length;
                    line.append('\t').append(n);
                    for (int i = 0; i < n; i++) {
                        line.append('\t').append(escape(strings[i]));
                    }
                    lines.add(line.toString());
                }
                int[] carriers;
                if (bucketList == null) {
                    carriers = trues(derived);
                } else {
                    int index = Collections.binarySearch(bucketList, contig + ":" + bucket);
                    if (index < 0) {
                        carriers = new int[0];
                    } else {
                        int bit = bucketList.size() - 1 - index + 2;
                        boolean[] set = new boolean[ids.length];
                        for (int p = 0; p < ids.length; p++) {
                            set[p] = bucketMasks[p] != null && bucketMasks[p].testBit(bit);
                        }
                        carriers = trues(set);
                    }
                }
                lines.add("C\t" + joinIds(carriers, ids));
                block("D\tB\t" + escape(contig) + "\t" + bucket, lines, bucketLines);
            }
        }
        line("N\t" + escape(group) + "\t" + escape(dirName) + "\t" + ids.length + "\t" + allSpecs.size());
        StringBuilder patientLine = new StringBuilder("P\t");
        for (int p = 0; p < ids.length; p++) {
            if (p > 0) {
                patientLine.append(',');
            }
            patientLine.append(ids[p]);
        }
        line(patientLine.toString());
        for (String l : bucketLines) {
            line(l);
        }
        dumpInfo(dir, specIndex);
    }

    private void dumpInfo(File dir, String[] specIndex) throws Exception {
        String[] files = dir.list((d, name) -> name.endsWith("infoStore.javabin"));
        TreeMap<String, String> columns = new TreeMap<>();
        for (String file : files == null ? new String[0] : files) {
            columns.put(file.replace("_infoStore.javabin", ""), file);
        }
        for (Map.Entry<String, String> column : columns.entrySet()) {
            FileBackedByteIndexedInfoStore info = (FileBackedByteIndexedInfoStore) readObject(new File(dir, column.getValue()));
            Storage<String, Integer[]> values = open(info.getAllValues(), dir);
            List<String> keys = new ArrayList<>(values.keys());
            Collections.sort(keys);
            line(
                "I\t" + escape(column.getKey()) + "\t" + (info.isContinuous ? 1 : 0) + "\t" + keys.size() + "\t"
                    + escape(String.valueOf(info.description))
            );
            List<String> keyLines = new ArrayList<>();
            for (String key : keys) {
                TreeSet<String> specs = new TreeSet<>();
                for (Integer id : values.get(key)) {
                    specs.add(specIndex[id]);
                }
                StringBuilder line = new StringBuilder("K\t").append(escape(key)).append('\t').append(specs.size());
                for (String spec : specs) {
                    line.append('\t').append(escape(spec));
                }
                keyLines.add(line.toString());
            }
            List<String> sink = new ArrayList<>();
            block("D\tK\t" + escape(column.getKey()), keyLines, sink);
            for (String l : sink) {
                line(l);
            }
            CompressedIndex index = info.continuousValueIndex;
            if (info.isContinuous && index != null) {
                @SuppressWarnings("unchecked")
                Map<Range<Float>, byte[]> ranges = (Map<Range<Float>, byte[]>) field(CompressedIndex.class, "compressedRangeMap", index);
                List<String> rangeLines = new ArrayList<>();
                for (Map.Entry<Range<Float>, byte[]> range : ranges.entrySet()) {
                    Range<Float> r = range.getKey();
                    StringBuilder line = new StringBuilder("R\t");
                    line.append(
                        bound(r.hasLowerBound(), r.hasLowerBound() ? r.lowerBoundType() : null, r.hasLowerBound() ? r.lowerEndpoint() : 0f)
                    );
                    line.append('\t');
                    line.append(
                        bound(r.hasUpperBound(), r.hasUpperBound() ? r.upperBoundType() : null, r.hasUpperBound() ? r.upperEndpoint() : 0f)
                    );
                    line.append('\t');
                    TreeMap<?, ?> map;
                    try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(range.getValue())))) {
                        map = (TreeMap<?, ?>) in.readObject();
                    }
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!first) {
                            line.append(';');
                        }
                        first = false;
                        line.append(String.format("%08x", Float.floatToRawIntBits((Float) entry.getKey()))).append('=');
                        List<Integer> indexes = new ArrayList<>();
                        for (Object key : (TreeSet<?>) entry.getValue()) {
                            indexes.add(Collections.binarySearch(keys, (String) key));
                        }
                        Collections.sort(indexes);
                        for (int i = 0; i < indexes.size(); i++) {
                            if (i > 0) {
                                line.append(',');
                            }
                            line.append(indexes.get(i));
                        }
                    }
                    rangeLines.add(line.toString());
                }
                Collections.sort(rangeLines);
                for (String l : rangeLines) {
                    line(l);
                }
            }
        }
    }

    private static String bound(boolean bounded, BoundType type, float value) {
        if (!bounded) {
            return "u";
        }
        return (type == BoundType.CLOSED ? "c:" : "o:") + String.format("%08x", Float.floatToRawIntBits(value));
    }

    private static int[] positions(VariantMask mask, int patientCount) {
        if (mask instanceof VariantMaskSparseImpl sparse) {
            return sparse.getPatientIndexes().stream().mapToInt(Integer::intValue).sorted().toArray();
        }
        BigInteger bits = ((VariantMaskBitmaskImpl) mask).getBitmask();
        byte[] bytes = bits.toByteArray();
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[bytes.length - 1 - i] & 0xff;
            while (b != 0) {
                int bit = i * 8 + Integer.numberOfTrailingZeros(b);
                if (bit >= 2 && bit < patientCount + 2) {
                    found.add(bit - 2);
                }
                b &= b - 1;
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] trues(boolean[] set) {
        int n = 0;
        int[] out = new int[set.length];
        for (int p = 0; p < set.length; p++) {
            if (set[p]) {
                out[n++] = p;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static String joinIds(int[] positions, int[] ids) {
        int[] mapped = new int[positions.length];
        for (int i = 0; i < positions.length; i++) {
            mapped[i] = ids[positions[i]];
        }
        Arrays.sort(mapped);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < mapped.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(mapped[i]);
        }
        return out.toString();
    }

    private void block(String prefix, List<String> lines, List<String> sink) throws Exception {
        if (!digest) {
            sink.addAll(lines);
            return;
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String l : lines) {
            sha.update((l + "\n").getBytes(StandardCharsets.UTF_8));
        }
        sink.add(prefix + "\t" + lines.size() + "\t" + HEX.formatHex(sha.digest()));
    }

    private void line(String line) throws IOException {
        out.write(line);
        out.write('\n');
    }

    /** Escapes backslash, tab, newline and carriage return, and writes an unpaired surrogate as {@code \\uXXXX}. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                out.append(c).append(s.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Object readObject(File file) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            return in.readObject();
        }
    }

    private static Object field(Class<?> type, String name, Object target) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Reads a storage's values at their recorded offsets, read only, decoded by the storage's own {@code readObject(byte[])}. */
    static final class Storage<K, V extends Serializable> implements Closeable {

        private final FileBackedByteIndexedStorage<K, V> storage;

        private final Map<K, Long[]> index;

        private final RandomAccessFile file;

        private final Method decode;

        @SuppressWarnings("unchecked")
        Storage(FileBackedByteIndexedStorage<K, V> storage, File dir) throws Exception {
            this.storage = storage;
            this.index = (Map<K, Long[]>) field(FileBackedByteIndexedStorage.class, "index", storage);
            File recorded = (File) field(FileBackedByteIndexedStorage.class, "storageFile", storage);
            this.file = new RandomAccessFile(new File(dir, recorded.getName()), "r");
            Method found = null;
            for (Class<?> c = storage.getClass(); c != null && found == null; c = c.getSuperclass()) {
                try {
                    found = c.getDeclaredMethod("readObject", byte[].class);
                } catch (NoSuchMethodException e) {
                    found = null;
                }
            }
            found.setAccessible(true);
            this.decode = found;
        }

        @Override
        public void close() throws IOException {
            file.close();
        }

        Set<K> keys() {
            return index.keySet();
        }

        @SuppressWarnings("unchecked")
        V get(K key) throws IOException {
            Long[] location = index.get(key);
            if (location == null || location[1].intValue() <= 0) {
                return null;
            }
            byte[] buffer = new byte[location[1].intValue()];
            file.seek(location[0]);
            file.readFully(buffer);
            try {
                return (V) decode.invoke(storage, (Object) buffer);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IOException(e);
            }
        }
    }
}
