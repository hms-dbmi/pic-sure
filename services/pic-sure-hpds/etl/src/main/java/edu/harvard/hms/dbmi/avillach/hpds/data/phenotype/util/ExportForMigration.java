package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

// spotless:off
/**
 * Exports a javabin phenotype store, every observation exactly as stored, for {@code hpds-etl migrate}.
 *
 * <p> Reads {@code columnMeta.javabin} and {@code allObservationsStore.javabin} from the store directory, decrypting each cube with the
 * {@code DEFAULT} key loaded from {@code encryption_key} in that directory, and writes two files to the export directory:
 * {@link #CONCEPTS_FILE} and {@link #PATIENTS_FILE}. Nothing is written to the store directory, and the export directory may be neither
 * the store directory, inside it, nor a directory that contains it, compared by real path so a symbolic link cannot hide either.
 *
 * <p> Both files are encrypted with the store's {@code DEFAULT} key, because they hold every observation in the clear otherwise. The
 * export directory is created mode {@code 0700} and both files mode {@code 0600}. Files a previous export left behind are deleted first.
 * Both files are written under temporary names; {@link #PATIENTS_FILE} is renamed into place first and {@link #CONCEPTS_FILE} last, so
 * a reader that finds {@link #CONCEPTS_FILE} finds a complete export.
 *
 * <p> Each file is an encrypted container:
 *
 * <pre>
 * magic       8 bytes   "HPDSMIG2"
 * version     int       2
 * export_id   16 bytes  random, the same in both files of one export
 * then frames until the one marked final, each:
 *   length    int       byte length of the frame
 *   frame     Crypto.encryptData(plaintext): int ivLength, iv, ciphertext and tag
 * </pre>
 *
 * whose plaintext is {@code export_id} (16 bytes), a {@code kind} byte (1 for {@link #CONCEPTS_FILE}, 2 for {@link #PATIENTS_FILE}),
 * the {@code long} frame index from 0, a {@code final} byte (1 on the last frame, else 0), then at most {@link #FRAME_DATA_BYTES} bytes of
 * the file's content. Concatenating the content of every frame gives the content described below.
 *
 * <p> The content of {@link #CONCEPTS_FILE} is a big-endian {@link DataOutputStream} stream of one record per metastore entry, in
 * metastore (raw key) order, each a tag byte 1 followed by:
 *
 * <pre>
 * key            string        the metastore key exactly as stored
 * key_flags      byte          bit 0: the key contains U+FFFD
 * value_type     byte          0 Double cube, 1 String cube
 * name           string        ColumnMeta.name
 * width          int           ColumnMeta.widthInBytes
 * column_offset  int           ColumnMeta.columnOffset
 * categorical    byte          ColumnMeta.categorical
 * category_count int           -1 when ColumnMeta.categoryValues is null, then that many strings
 * min, max       nullable double
 * obs_offset     long          ColumnMeta.allObservationsOffset
 * obs_end        long          ColumnMeta.allObservationsLength, which holds the end position in the javabin
 * obs_count      int           ColumnMeta.observationCount
 * patient_count  int           ColumnMeta.patientCount
 * has_timestamp  byte          ColumnMeta.hasTimestamp
 * ts_min, ts_max nullable long
 * cube_name      string        PhenoCube.name
 * column_width   int           PhenoCube.columnWidth
 * n              int           length of PhenoCube.sortedByKey, then n observations in sortedByKey order:
 *   patient      int
 *   value        long (Double.doubleToRawLongBits) for a Double cube, string for a String cube
 *   timestamp    nullable long
 * </pre>
 *
 * and finally a tag byte 0, the {@code long} number of records and the {@code long} number of observations. A string is an {@code int}
 * UTF-8 byte length, {@code -1} for null, then the bytes. A nullable double or long is a byte, 0 for null or 1, then the value when
 * present. The content of {@link #PATIENTS_FILE} is the store's patient ID set, one decimal ID per line in ascending order.
 *
 * <p> Usage: {@code ExportForMigration [store directory [export directory]]}, defaulting to {@code /opt/local/hpds} and
 * {@value #DEFAULT_EXPORT_DIRECTORY}.
 */
// spotless:on
public class ExportForMigration {

    /** The file holding the concepts and their observations. */
    public static final String CONCEPTS_FILE = "concepts.bin";

    /** The file holding the patient ID set. */
    public static final String PATIENTS_FILE = "patients.txt";

    /** Magic bytes at the start of both export files. */
    public static final byte[] MAGIC = "HPDSMIG2".getBytes(StandardCharsets.US_ASCII);

    /** Version of the export layout. */
    public static final int VERSION = 2;

    /** Length of the random export id. */
    public static final int EXPORT_ID_BYTES = 16;

    /** The {@code kind} byte of {@link #CONCEPTS_FILE} frames. */
    public static final byte CONCEPTS_KIND = 1;

    /** The {@code kind} byte of {@link #PATIENTS_FILE} frames. */
    public static final byte PATIENTS_KIND = 2;

    /** Most content bytes one frame carries. */
    public static final int FRAME_DATA_BYTES = 8 << 20;

    /** The export directory used when none is given. */
    public static final String DEFAULT_EXPORT_DIRECTORY = "/opt/local/export";

    private static final String DEFAULT_STORE_DIRECTORY = "/opt/local/hpds";

    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path storeDirectory;

    private final Path exportDirectory;

    /**
     * @param storeDirectory directory holding {@code columnMeta.javabin}, {@code allObservationsStore.javabin} and {@code encryption_key}
     * @param exportDirectory directory to write the export to; created mode {@code 0700} if missing
     */
    public ExportForMigration(Path storeDirectory, Path exportDirectory) {
        this.storeDirectory = storeDirectory;
        this.exportDirectory = exportDirectory;
    }

    /**
     * Runs the export from the command line.
     *
     * @param args optional store directory, then optional export directory
     * @throws Exception when the store cannot be read or the export cannot be written
     */
    public static void main(String[] args) throws Exception {
        Path store = Path.of(args.length > 0 ? args[0] : DEFAULT_STORE_DIRECTORY);
        Path export = Path.of(args.length > 1 ? args[1] : DEFAULT_EXPORT_DIRECTORY);
        Summary summary = new ExportForMigration(store, export).export();
        System.out.println(
            "Exported " + summary.concepts() + " concepts, " + summary.observations() + " observations and " + summary.patients()
                + " patients to " + export
        );
    }

    /**
     * Counts of what one export wrote.
     *
     * @param concepts metastore entries written
     * @param observations observations written
     * @param patients patient IDs written
     */
    public record Summary(long concepts, long observations, long patients) {
    }

    /**
     * Loads the key, reads the metastore and every cube, and writes the encrypted export.
     *
     * @return what was written
     * @throws IOException when a file cannot be read or written
     * @throws ClassNotFoundException when the javabins hold a class this build does not have
     * @throws IllegalStateException when the key cannot be loaded, the export directory is the store directory, inside it, or contains it,
     *         or the store holds something the export cannot represent exactly: a cube of a type other than Double or String, a null value,
     *         a string with an unpaired surrogate, or a String cube whose categoryMap disagrees with its observations
     */
    public Summary export() throws IOException, ClassNotFoundException {
        Path store = storeDirectory.toRealPath();
        Path export = realPathAllowingMissing(exportDirectory);
        if (export.startsWith(store) || store.startsWith(export)) {
            throw new IllegalStateException(
                "the export directory " + export + " must be outside the store directory " + store + " and must not contain it"
            );
        }
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, store.resolve("encryption_key").toString());
        if (!Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)) {
            throw new IllegalStateException("no encryption key could be loaded from " + store.resolve("encryption_key"));
        }

        TreeMap<String, ColumnMeta> metastore;
        Set<Integer> allIds;
        try (
            ObjectInputStream in =
                new ObjectInputStream(new GZIPInputStream(new FileInputStream(store.resolve("columnMeta.javabin").toFile())))
        ) {
            metastore = readObject(in);
            allIds = readObject(in);
        }

        createPrivateDirectory(export);
        for (String name : List.of(CONCEPTS_FILE, PATIENTS_FILE)) {
            Files.deleteIfExists(export.resolve(name));
            Files.deleteIfExists(export.resolve(name + TEMPORARY_SUFFIX));
        }
        byte[] exportId = new byte[EXPORT_ID_BYTES];
        new SecureRandom().nextBytes(exportId);

        Path conceptsTmp = export.resolve(CONCEPTS_FILE + TEMPORARY_SUFFIX);
        Path patientsTmp = export.resolve(PATIENTS_FILE + TEMPORARY_SUFFIX);
        long concepts = 0;
        long observations = 0;
        try {
            try (
                RandomAccessFile observationsStore =
                    new RandomAccessFile(store.resolve("allObservationsStore.javabin").toFile(), "r"); DataOutputStream out =
                        new DataOutputStream(
                            new BufferedOutputStream(
                                new EncryptingOutputStream(createPrivateFile(conceptsTmp), exportId, CONCEPTS_KIND), 1 << 20
                            )
                        )
            ) {
                for (Map.Entry<String, ColumnMeta> entry : metastore.entrySet()) {
                    PhenoCube<?> cube = readCube(observationsStore, entry.getKey(), entry.getValue());
                    observations += writeConcept(out, entry.getKey(), entry.getValue(), cube);
                    concepts++;
                }
                out.writeByte(0);
                out.writeLong(concepts);
                out.writeLong(observations);
            }
            try (
                OutputStream out =
                    new BufferedOutputStream(new EncryptingOutputStream(createPrivateFile(patientsTmp), exportId, PATIENTS_KIND), 1 << 20)
            ) {
                for (Integer id : new TreeSet<>(allIds)) {
                    out.write((id + "\n").getBytes(StandardCharsets.US_ASCII));
                }
            }
            Files.move(patientsTmp, export.resolve(PATIENTS_FILE), StandardCopyOption.REPLACE_EXISTING);
            Files.move(conceptsTmp, export.resolve(CONCEPTS_FILE), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(conceptsTmp);
            Files.deleteIfExists(patientsTmp);
        }
        return new Summary(concepts, observations, allIds.size());
    }

    /**
     * Resolves {@code path} to its real path when it exists, else the real path of its nearest existing ancestor with the missing names
     * appended, so a symbolic link anywhere along an existing prefix is followed.
     */
    private static Path realPathAllowingMissing(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            missing.push(existing.getFileName());
            existing = existing.getParent();
        }
        Path resolved = existing == null ? absolute.getRoot() : existing.toRealPath();
        while (!missing.isEmpty()) {
            resolved = resolved.resolve(missing.pop());
        }
        return resolved;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (!posix()) {
            Files.createDirectories(directory);
            return;
        }
        Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rwx------");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (directory.getParent() != null) {
                Files.createDirectories(directory.getParent());
            }
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(owner));
        }
        Files.setPosixFilePermissions(directory, owner);
    }

    private static FileOutputStream createPrivateFile(Path file) throws IOException {
        FileAttribute<?>[] attributes =
            posix() ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        Files.createFile(file, attributes);
        return new FileOutputStream(file.toFile());
    }

    @SuppressWarnings("unchecked")
    private static <T> T readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        return (T) in.readObject();
    }

    private static PhenoCube<?> readCube(RandomAccessFile observationsStore, String key, ColumnMeta meta)
        throws IOException, ClassNotFoundException {
        long length = meta.getAllObservationsLength() - meta.getAllObservationsOffset();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalStateException("concept " + key + " has an invalid javabin range");
        }
        byte[] buffer = new byte[(int) length];
        observationsStore.seek(meta.getAllObservationsOffset());
        observationsStore.readFully(buffer);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)))) {
            return (PhenoCube<?>) in.readObject();
        }
    }

    private static long writeConcept(DataOutputStream out, String key, ColumnMeta meta, PhenoCube<?> cube) throws IOException {
        boolean text;
        if (String.class.equals(cube.vType)) {
            text = true;
        } else if (Double.class.equals(cube.vType)) {
            text = false;
        } else {
            throw new IllegalStateException("concept " + key + " holds " + cube.vType + " values, not Double or String");
        }
        KeyAndValue<?>[] sortedByKey = cube.sortedByKey();
        int n = sortedByKey == null ? 0 : sortedByKey.length;
        if (text) {
            requireCategoryMapMatches(key, cube, sortedByKey, n);
        }
        out.writeByte(1);
        writeString(out, key, key);
        out.writeByte(key.indexOf('�') >= 0 ? 1 : 0);
        out.writeByte(text ? 1 : 0);

        writeString(out, meta.getName(), key);
        out.writeInt(meta.getWidthInBytes());
        out.writeInt(meta.getColumnOffset());
        out.writeBoolean(meta.isCategorical());
        List<String> categoryValues = meta.getCategoryValues();
        if (categoryValues == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(categoryValues.size());
            for (String value : categoryValues) {
                writeString(out, value, key);
            }
        }
        writeNullableDouble(out, meta.getMin());
        writeNullableDouble(out, meta.getMax());
        out.writeLong(meta.getAllObservationsOffset());
        out.writeLong(meta.getAllObservationsLength());
        out.writeInt((int) meta.getObservationCount());
        out.writeInt((int) meta.getPatientCount());
        out.writeBoolean(meta.hasTimestamp());
        writeNullableLong(out, meta.getTimestampMin());
        writeNullableLong(out, meta.getTimestampMax());

        writeString(out, cube.name, key);
        out.writeInt(cube.getColumnWidth());
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            KeyAndValue<?> observation = sortedByKey[i];
            Object value = observation.getValue();
            if (value == null) {
                throw new IllegalStateException("concept " + key + " has a null value at observation " + i);
            }
            out.writeInt(observation.getKey());
            if (text) {
                writeString(out, (String) value, key);
            } else {
                out.writeLong(Double.doubleToRawLongBits((Double) value));
            }
            writeNullableLong(out, observation.getTimestamp());
        }
        return n;
    }

    /**
     * Checks that a String cube's categoryMap, which the Java service answers category filters from, maps exactly the values in sortedByKey
     * to the patients holding each. A null categoryMap counts as empty. The message never names a value or a patient.
     */
    private static void requireCategoryMapMatches(String key, PhenoCube<?> cube, KeyAndValue<?>[] sortedByKey, int n) {
        TreeMap<String, TreeSet<Integer>> expected = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            Object value = sortedByKey[i].getValue();
            if (value != null) {
                expected.computeIfAbsent((String) value, v -> new TreeSet<>()).add(sortedByKey[i].getKey());
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, TreeSet<Integer>> recorded = (Map<String, TreeSet<Integer>>) (Map<?, ?>) cube.getCategoryMap();
        Map<String, TreeSet<Integer>> actual = recorded == null ? Map.of() : recorded;
        if (!Objects.equals(new TreeMap<>(actual), expected)) {
            throw new IllegalStateException(
                "concept " + key + " has a categoryMap with " + actual.size() + " values that disagrees with the " + expected.size()
                    + " distinct values of its observations"
            );
        }
    }

    private static void writeString(DataOutputStream out, String value, String concept) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        requireWellFormed(value, concept);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void requireWellFormed(String value, String concept) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalStateException(
                    "concept " + concept + " holds a string with an unpaired surrogate at index " + i
                        + ", which UTF-8 cannot represent exactly"
                );
            }
        }
    }

    private static void writeNullableDouble(DataOutputStream out, Double value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(Double.doubleToRawLongBits(value));
        }
    }

    private static void writeNullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(value);
        }
    }

    /**
     * Writes the encrypted container described on {@link ExportForMigration}: the header, then the content in frames of at most
     * {@link #FRAME_DATA_BYTES} bytes, each sealed with {@link Crypto#encryptData(byte[])} under the {@code DEFAULT} key. Closing it writes
     * the final frame, which may carry no content, then syncs and closes the file.
     */
    static final class EncryptingOutputStream extends OutputStream {

        private final FileOutputStream file;

        private final byte[] exportId;

        private final byte kind;

        private final byte[] buffer = new byte[FRAME_DATA_BYTES];

        private int buffered;

        private long index;

        private boolean closed;

        /**
         * @param file the destination, owned and closed by this stream
         * @param exportId the export's random id
         * @param kind {@link #CONCEPTS_KIND} or {@link #PATIENTS_KIND}
         * @throws IOException when the header cannot be written
         */
        EncryptingOutputStream(FileOutputStream file, byte[] exportId, byte kind) throws IOException {
            this.file = file;
            this.exportId = exportId.clone();
            this.kind = kind;
            DataOutputStream header = new DataOutputStream(file);
            header.write(MAGIC);
            header.writeInt(VERSION);
            header.write(this.exportId);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            while (length > 0) {
                if (buffered == buffer.length) {
                    writeFrame(false);
                }
                int take = Math.min(length, buffer.length - buffered);
                System.arraycopy(bytes, offset, buffer, buffered, take);
                buffered += take;
                offset += take;
                length -= take;
            }
        }

        private void writeFrame(boolean last) throws IOException {
            ByteBuffer plaintext = ByteBuffer.allocate(EXPORT_ID_BYTES + 1 + 8 + 1 + buffered);
            plaintext.put(exportId).put(kind).putLong(index).put((byte) (last ? 1 : 0)).put(buffer, 0, buffered);
            byte[] frame = Crypto.encryptData(plaintext.array());
            file.write(ByteBuffer.allocate(4).putInt(frame.length).array());
            file.write(frame);
            index++;
            buffered = 0;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try (FileOutputStream out = file) {
                writeFrame(true);
                out.flush();
                out.getFD().sync();
            }
        }
    }
}
