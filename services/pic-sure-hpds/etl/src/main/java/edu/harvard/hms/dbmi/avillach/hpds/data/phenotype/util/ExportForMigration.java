package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

/**
 * Exports a javabin phenotype store, every observation exactly as stored, for {@code hpds-etl migrate}.
 *
 * <p> Reads {@code columnMeta.javabin} and {@code allObservationsStore.javabin} from the store directory, decrypting each cube with the
 * {@code DEFAULT} key loaded from {@code encryption_key} in that directory, and writes two files to the export directory:
 * {@link #CONCEPTS_FILE} and {@link #PATIENTS_FILE}. Nothing is written to the store directory. Each file is written under a temporary name
 * and renamed into place once complete.
 *
 * <p> {@link #CONCEPTS_FILE} is a big-endian {@link DataOutputStream} stream:
 *
 * <pre> magic 8 bytes "HPDSMIG1" version int 1 then, per metastore entry in metastore (raw key) order: tag byte 1 key string the metastore
 * key exactly as stored key_flags byte bit 0: the key contains U+FFFD value_type byte 0 Double cube, 1 String cube name string
 * ColumnMeta.name width int ColumnMeta.widthInBytes column_offset int ColumnMeta.columnOffset categorical byte ColumnMeta.categorical
 * category_count int -1 when ColumnMeta.categoryValues is null category_count strings min, max nullable double obs_offset long
 * ColumnMeta.allObservationsOffset obs_length long ColumnMeta.allObservationsLength (the end position in the javabin) obs_count int
 * ColumnMeta.observationCount patient_count int ColumnMeta.patientCount has_timestamp byte ColumnMeta.hasTimestamp ts_min, ts_max nullable
 * long cube_name string PhenoCube.name column_width int PhenoCube.columnWidth n int length of PhenoCube.sortedByKey n observations in
 * sortedByKey order: patient int value long (Double.doubleToRawLongBits) for a Double cube, string for a String cube timestamp nullable
 * long end: tag byte 0 concepts long entries written observations long observations written </pre>
 *
 * A string is an {@code int} UTF-8 byte length, {@code -1} for null, then the bytes. A nullable double or long is a byte, 0 for null or 1,
 * then the value when present. {@link #PATIENTS_FILE} holds the store's patient ID set, one decimal ID per line in ascending order.
 *
 * <p> Usage: {@code ExportForMigration [store directory [export directory]]}, defaulting to {@code /opt/local/hpds} and
 * {@code <store directory>/migration}.
 */
public class ExportForMigration {

    /** The file holding the concepts and their observations. */
    public static final String CONCEPTS_FILE = "concepts.bin";

    /** The file holding the patient ID set. */
    public static final String PATIENTS_FILE = "patients.txt";

    /** Magic bytes at the start of {@link #CONCEPTS_FILE}. */
    public static final byte[] MAGIC = "HPDSMIG1".getBytes(StandardCharsets.US_ASCII);

    /** Version of the {@link #CONCEPTS_FILE} layout. */
    public static final int VERSION = 1;

    private static final String DEFAULT_STORE_DIRECTORY = "/opt/local/hpds";

    private final Path storeDirectory;

    private final Path exportDirectory;

    /**
     * @param storeDirectory directory holding {@code columnMeta.javabin}, {@code allObservationsStore.javabin} and {@code encryption_key}
     * @param exportDirectory directory to write the export to; created if missing
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
        Path export = args.length > 1 ? Path.of(args[1]) : store.resolve("migration");
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
     * Loads the key, reads the metastore and every cube, and writes the export.
     *
     * @return what was written
     * @throws IOException when a file cannot be read or written
     * @throws ClassNotFoundException when the javabins hold a class this build does not have
     * @throws IllegalStateException when the key cannot be loaded, an export file would land in the store directory, or the store holds
     *         something the export cannot represent exactly: a cube of a type other than Double or String, a null value, or a string with
     *         an unpaired surrogate
     */
    public Summary export() throws IOException, ClassNotFoundException {
        Path store = storeDirectory.toAbsolutePath().normalize();
        Path export = exportDirectory.toAbsolutePath().normalize();
        if (export.equals(store)) {
            throw new IllegalStateException("the export directory must not be the store directory " + store);
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

        Files.createDirectories(export);
        Path conceptsTmp = export.resolve(CONCEPTS_FILE + ".tmp");
        long concepts = 0;
        long observations = 0;
        try (
            RandomAccessFile observationsStore =
                new RandomAccessFile(store.resolve("allObservationsStore.javabin").toFile(), "r"); OutputStream file =
                    Files.newOutputStream(conceptsTmp); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file, 1 << 20))
        ) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            for (Map.Entry<String, ColumnMeta> entry : metastore.entrySet()) {
                PhenoCube<?> cube = readCube(observationsStore, entry.getValue());
                observations += writeConcept(out, entry.getKey(), entry.getValue(), cube);
                concepts++;
            }
            out.writeByte(0);
            out.writeLong(concepts);
            out.writeLong(observations);
        }

        Path patientsTmp = export.resolve(PATIENTS_FILE + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(patientsTmp, StandardCharsets.US_ASCII)) {
            for (Integer id : new TreeSet<>(allIds)) {
                writer.write(Integer.toString(id));
                writer.write('\n');
            }
        }
        Files.move(conceptsTmp, export.resolve(CONCEPTS_FILE), StandardCopyOption.REPLACE_EXISTING);
        Files.move(patientsTmp, export.resolve(PATIENTS_FILE), StandardCopyOption.REPLACE_EXISTING);
        return new Summary(concepts, observations, allIds.size());
    }

    @SuppressWarnings("unchecked")
    private static <T> T readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        return (T) in.readObject();
    }

    private static PhenoCube<?> readCube(RandomAccessFile observationsStore, ColumnMeta meta) throws IOException, ClassNotFoundException {
        long length = meta.getAllObservationsLength() - meta.getAllObservationsOffset();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalStateException("concept " + meta.getName() + " has an invalid javabin range");
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
        KeyAndValue<?>[] sortedByKey = cube.sortedByKey();
        int n = sortedByKey == null ? 0 : sortedByKey.length;
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            KeyAndValue<?> observation = sortedByKey[i];
            Object value = observation.getValue();
            if (value == null) {
                throw new IllegalStateException("concept " + key + " has a null value for patient " + observation.getKey());
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
}
