package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

class ExportForMigrationTest {

    /**
     * System property naming a directory to copy the golden export and its key into. The Rust test
     * {@code migrate_reads_the_java_golden_export} reads the copy checked in at
     * {@code services/pic-sure-hpds-rs/crates/hpds-etl/tests/fixtures/java-export}.
     */
    private static final String GOLDEN_PROPERTY = "hpds.migration.golden";

    private static final String KEY = "0123456789ABCDEF0123456789ABCDEF";

    private static final String NUMERIC = "\\num\\a�b\\";

    private static final String TEXT = "\\text\\x\\";

    private static final String EMPTY = "\\empty\\";

    private static final String AWKWARD = "multi\nline, \"quoted\" 😀 µ";

    @Test
    void exportsEveryObservationExactlyAsStored(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path export = dir.resolve("export");
        Files.createDirectories(store);
        writeStore(store);
        byte[] metaBefore = Files.readAllBytes(store.resolve("columnMeta.javabin"));
        byte[] observationsBefore = Files.readAllBytes(store.resolve("allObservationsStore.javabin"));

        ExportForMigration.Summary summary = new ExportForMigration(store, export).export();

        assertEquals(new ExportForMigration.Summary(3, 5, 4), summary);
        assertArrayEquals(metaBefore, Files.readAllBytes(store.resolve("columnMeta.javabin")));
        assertArrayEquals(observationsBefore, Files.readAllBytes(store.resolve("allObservationsStore.javabin")));
        try (var files = Files.list(store)) {
            assertEquals(3, files.count());
        }
        try (var files = Files.list(export)) {
            assertEquals(
                List.of(ExportForMigration.CONCEPTS_FILE, ExportForMigration.PATIENTS_FILE),
                files.map(p -> p.getFileName().toString()).sorted().toList()
            );
        }
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(export)));
        for (String name : List.of(ExportForMigration.CONCEPTS_FILE, ExportForMigration.PATIENTS_FILE)) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(export.resolve(name))));
        }
        byte[] rawConcepts = Files.readAllBytes(export.resolve(ExportForMigration.CONCEPTS_FILE));
        assertFalse(contains(rawConcepts, TEXT.getBytes(StandardCharsets.UTF_8)), "concepts.bin holds a concept path in the clear");
        assertArrayEquals(
            exportId(export.resolve(ExportForMigration.CONCEPTS_FILE)), exportId(export.resolve(ExportForMigration.PATIENTS_FILE))
        );

        assertEquals(
            "-4\n1\n7\n9\n",
            new String(
                decrypt(export.resolve(ExportForMigration.PATIENTS_FILE), ExportForMigration.PATIENTS_KIND), StandardCharsets.US_ASCII
            )
        );

        byte[] content = decrypt(export.resolve(ExportForMigration.CONCEPTS_FILE), ExportForMigration.CONCEPTS_KIND);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(content))) {
            assertEquals(1, in.readByte());
            assertEquals(EMPTY, readString(in));
            assertEquals(0, in.readByte());
            assertEquals(1, in.readByte());
            assertEquals(EMPTY, readString(in));
            assertEquals(0, in.readInt());
            assertEquals(0, in.readInt());
            assertTrue(in.readBoolean());
            assertEquals(0, in.readInt());
            assertFalse(in.readBoolean());
            assertFalse(in.readBoolean());
            in.readLong();
            in.readLong();
            assertEquals(0, in.readInt());
            assertEquals(0, in.readInt());
            assertFalse(in.readBoolean());
            assertFalse(in.readBoolean());
            assertFalse(in.readBoolean());
            assertEquals(EMPTY, readString(in));
            assertEquals(0, in.readInt());
            assertEquals(0, in.readInt());

            assertEquals(1, in.readByte());
            assertEquals(NUMERIC, readString(in));
            assertEquals(1, in.readByte());
            assertEquals(0, in.readByte());
            assertEquals(NUMERIC, readString(in));
            assertEquals(8, in.readInt());
            assertEquals(3, in.readInt());
            assertFalse(in.readBoolean());
            assertEquals(-1, in.readInt());
            assertTrue(in.readBoolean());
            assertEquals(Double.doubleToRawLongBits(-0.0), in.readLong());
            assertTrue(in.readBoolean());
            assertEquals(Double.doubleToRawLongBits(4.9E-324), in.readLong());
            in.readLong();
            in.readLong();
            assertEquals(3, in.readInt());
            assertEquals(2, in.readInt());
            assertTrue(in.readBoolean());
            assertTrue(in.readBoolean());
            assertEquals(5L, in.readLong());
            assertTrue(in.readBoolean());
            assertEquals(6L, in.readLong());
            assertEquals(NUMERIC, readString(in));
            assertEquals(8, in.readInt());
            assertEquals(3, in.readInt());
            assertEquals(1, in.readInt());
            assertEquals(Double.doubleToRawLongBits(2.5), in.readLong());
            assertFalse(in.readBoolean());
            assertEquals(1, in.readInt());
            assertEquals(Double.doubleToRawLongBits(-0.0), in.readLong());
            assertTrue(in.readBoolean());
            assertEquals(Long.MIN_VALUE, in.readLong());
            assertEquals(7, in.readInt());
            assertEquals(0x7ff8000000000000L, in.readLong());
            assertTrue(in.readBoolean());
            assertEquals(1_700_000_000_000L, in.readLong());

            assertEquals(1, in.readByte());
            assertEquals(TEXT, readString(in));
            assertEquals(0, in.readByte());
            assertEquals(1, in.readByte());
            assertEquals(TEXT, readString(in));
            assertEquals(23, in.readInt());
            assertEquals(0, in.readInt());
            assertTrue(in.readBoolean());
            assertEquals(2, in.readInt());
            assertEquals("B", readString(in));
            assertEquals(AWKWARD, readString(in));
            assertFalse(in.readBoolean());
            assertFalse(in.readBoolean());
            in.readLong();
            in.readLong();
            assertEquals(2, in.readInt());
            assertEquals(2, in.readInt());
            assertFalse(in.readBoolean());
            assertFalse(in.readBoolean());
            assertFalse(in.readBoolean());
            assertEquals(TEXT, readString(in));
            assertEquals(23, in.readInt());
            assertEquals(2, in.readInt());
            assertEquals(-4, in.readInt());
            assertEquals(AWKWARD, readString(in));
            assertFalse(in.readBoolean());
            assertEquals(9, in.readInt());
            assertEquals("B", readString(in));
            assertTrue(in.readBoolean());
            assertEquals(-5L, in.readLong());

            assertEquals(0, in.readByte());
            assertEquals(3L, in.readLong());
            assertEquals(5L, in.readLong());
            assertEquals(-1, in.read());
        }
    }

    @Test
    void splitsLargeContentIntoFrames(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("key"), KEY);
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, dir.resolve("key").toString());
        byte[] content = new byte[ExportForMigration.FRAME_DATA_BYTES * 2 + 5];
        Arrays.fill(content, (byte) 'x');
        Path file = dir.resolve("framed");
        byte[] id = new byte[ExportForMigration.EXPORT_ID_BYTES];
        try (
            var out = new ExportForMigration.EncryptingOutputStream(
                new java.io.FileOutputStream(file.toFile()), id, ExportForMigration.CONCEPTS_KIND
            )
        ) {
            out.write(content, 0, content.length);
        }
        assertEquals(3, frameCount(file));
        assertArrayEquals(content, decrypt(file, ExportForMigration.CONCEPTS_KIND));
    }

    @Test
    void deletesFilesALaterExportWouldNotReplace(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path export = dir.resolve("export");
        Files.createDirectories(store);
        Files.createDirectories(export);
        writeStore(store);
        Files.writeString(export.resolve(ExportForMigration.CONCEPTS_FILE + ".tmp"), "stale");
        Files.writeString(export.resolve(ExportForMigration.PATIENTS_FILE), "stale plaintext");
        Files.writeString(export.resolve("unrelated"), "kept");
        Files.setPosixFilePermissions(export, PosixFilePermissions.fromString("rwxr-xr-x"));

        new ExportForMigration(store, export).export();

        try (var files = Files.list(export)) {
            assertEquals(
                List.of(ExportForMigration.CONCEPTS_FILE, ExportForMigration.PATIENTS_FILE, "unrelated"),
                files.map(p -> p.getFileName().toString()).sorted().toList()
            );
        }
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(export)));
        assertEquals(
            "-4\n1\n7\n9\n",
            new String(
                decrypt(export.resolve(ExportForMigration.PATIENTS_FILE), ExportForMigration.PATIENTS_KIND), StandardCharsets.US_ASCII
            )
        );
    }

    @Test
    void refusesAnExportDirectoryInsideOrAroundTheStore(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Files.createDirectories(store);
        writeStore(store);
        Path link = dir.resolve("link");
        Files.createSymbolicLink(link, store);

        for (Path export : List.of(store, store.resolve("migration"), store.resolve("a/b"), dir, link.resolve("export"))) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new ExportForMigration(store, export).export());
            assertTrue(refused.getMessage().contains("must be outside the store directory"), refused.getMessage());
        }
        assertThrows(IllegalStateException.class, () -> new ExportForMigration(link, store.resolve("x")).export());
        try (var files = Files.list(store)) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void theDefaultExportDirectoryIsOutsideTheDefaultStore() {
        Path defaultStore = Path.of("/opt/local/hpds");
        Path defaultExport = Path.of(ExportForMigration.DEFAULT_EXPORT_DIRECTORY);
        assertFalse(defaultExport.startsWith(defaultStore));
        assertFalse(defaultStore.startsWith(defaultExport));
    }

    @Test
    void refusesACategoryMapThatDisagreesWithTheObservations(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Files.createDirectories(store);
        Files.writeString(store.resolve("encryption_key"), KEY + "\n");
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, store.resolve("encryption_key").toString());
        PhenoCube<String> text = new PhenoCube<>(TEXT, String.class);
        text.setSortedByKey(observations(new KeyAndValue<>(1, "a", null), new KeyAndValue<>(2, "b", null)));
        TreeMap<String, TreeSet<Integer>> wrong = new TreeMap<>();
        wrong.put("a", new TreeSet<>(List.of(1, 2)));
        wrong.put("b", new TreeSet<>(List.of(2)));
        text.setCategoryMap(wrong);
        TreeMap<String, ColumnMeta> metastore = new TreeMap<>();
        try (RandomAccessFile file = new RandomAccessFile(store.resolve("allObservationsStore.javabin").toFile(), "rw")) {
            ColumnMeta meta = append(file, text).setCategorical(true).setObservationCount(2).setPatientCount(2);
            meta.setCategoryValues(new ArrayList<>(List.of("a", "b")));
            metastore.put(TEXT, meta);
        }
        writeMetastore(store, metastore, List.of(1, 2));

        IllegalStateException refused =
            assertThrows(IllegalStateException.class, () -> new ExportForMigration(store, dir.resolve("export")).export());
        assertTrue(refused.getMessage().contains("categoryMap"), refused.getMessage());
        try (var files = Files.list(dir.resolve("export"))) {
            assertEquals(0, files.count());
        }
    }

    /**
     * Exports a store with non-negative patients that covers numbers, text, timestamps, a U+FFFD key collision, an empty concept, and a
     * ColumnMeta.name that differs from its key. With {@code -Dhpds.migration.golden=<dir>} the export and its key are copied there;
     * regenerate the Rust fixture with
     * {@code mvn -pl services/pic-sure-hpds/etl test -Dtest=ExportForMigrationTest -Dhpds.migration.golden=$PWD/services/pic-sure-hpds-rs/crates/hpds-etl/tests/fixtures/java-export}.
     */
    @Test
    void writesTheGoldenExportForRust(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path export = dir.resolve("export");
        Files.createDirectories(store);
        Files.writeString(store.resolve("encryption_key"), KEY + "\n");
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, store.resolve("encryption_key").toString());

        PhenoCube<Double> plain = new PhenoCube<>("\\golden\\ab\\", Double.class);
        plain.setSortedByKey(observations(new KeyAndValue<>(1, 1.0, null)));
        PhenoCube<Double> replacing = new PhenoCube<>("\\golden\\a�b\\", Double.class);
        replacing.setSortedByKey(
            observations(
                new KeyAndValue<>(2, 2.5, 1_700_000_000_000L), new KeyAndValue<>(2, -0.0, Long.MIN_VALUE),
                new KeyAndValue<>(3, 1.0E10, null), new KeyAndValue<>(4, Double.NaN, -5L)
            )
        );
        PhenoCube<String> text = new PhenoCube<>("\\golden\\text\\", String.class);
        text.setSortedByKey(
            observations(new KeyAndValue<>(0, "z", null), new KeyAndValue<>(0, AWKWARD, 10L), new KeyAndValue<>(6, "z", null))
        );
        text.setCategoryMap(categoryMap(text));
        PhenoCube<String> empty = new PhenoCube<>("\\golden\\empty\\", String.class);
        empty.setSortedByKey(observations());
        empty.setCategoryMap(new TreeMap<>());

        TreeMap<String, ColumnMeta> metastore = new TreeMap<>();
        try (RandomAccessFile file = new RandomAccessFile(store.resolve("allObservationsStore.javabin").toFile(), "rw")) {
            metastore.put(plain.name, append(file, plain).setWidthInBytes(8).setObservationCount(1).setPatientCount(1));
            metastore.put(
                replacing.name, append(file, replacing).setWidthInBytes(8).setColumnOffset(4).setObservationCount(4).setPatientCount(3)
            );
            ColumnMeta textMeta = append(file, text).setName("\\golden\\renamed\\").setWidthInBytes(22).setCategorical(true)
                .setObservationCount(3).setPatientCount(2);
            textMeta.setCategoryValues(new ArrayList<>(List.of(AWKWARD, "z")));
            metastore.put(text.name, textMeta);
            ColumnMeta emptyMeta = append(file, empty).setCategorical(true);
            emptyMeta.setCategoryValues(new ArrayList<>());
            metastore.put(empty.name, emptyMeta);
        }
        writeMetastore(store, metastore, List.of(0, 1, 2, 3, 4, 6, 8));

        assertEquals(new ExportForMigration.Summary(4, 8, 7), new ExportForMigration(store, export).export());
        assertEquals(
            "0\n1\n2\n3\n4\n6\n8\n",
            new String(
                decrypt(export.resolve(ExportForMigration.PATIENTS_FILE), ExportForMigration.PATIENTS_KIND), StandardCharsets.US_ASCII
            )
        );

        String golden = System.getProperty(GOLDEN_PROPERTY);
        if (golden != null && !golden.isBlank()) {
            Path target = Path.of(golden);
            Files.createDirectories(target);
            for (String name : List.of(ExportForMigration.CONCEPTS_FILE, ExportForMigration.PATIENTS_FILE)) {
                Files.copy(export.resolve(name), target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(store.resolve("encryption_key"), target.resolve("encryption_key"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static TreeMap<String, TreeSet<Integer>> categoryMap(PhenoCube<String> cube) {
        TreeMap<String, TreeSet<Integer>> map = new TreeMap<>();
        for (KeyAndValue<String> observation : cube.sortedByKey()) {
            map.computeIfAbsent(observation.getValue(), v -> new TreeSet<>()).add(observation.getKey());
        }
        return map;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] exportId(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            assertArrayEquals(ExportForMigration.MAGIC, in.readNBytes(8));
            assertEquals(ExportForMigration.VERSION, in.readInt());
            return in.readNBytes(ExportForMigration.EXPORT_ID_BYTES);
        }
    }

    private static int frameCount(Path file) throws IOException {
        int frames = 0;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            in.readNBytes(8 + 4 + ExportForMigration.EXPORT_ID_BYTES);
            while (in.available() > 0) {
                in.skipNBytes(in.readInt());
                frames++;
            }
        }
        return frames;
    }

    /** Opens an encrypted export file and returns its content, checking the frame headers. */
    private static byte[] decrypt(Path file, byte kind) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            assertArrayEquals(ExportForMigration.MAGIC, in.readNBytes(8));
            assertEquals(ExportForMigration.VERSION, in.readInt());
            byte[] id = in.readNBytes(ExportForMigration.EXPORT_ID_BYTES);
            boolean last = false;
            for (long index = 0; !last; index++) {
                byte[] frame = in.readNBytes(in.readInt());
                ByteBuffer plaintext = ByteBuffer.wrap(Crypto.decryptData(frame));
                byte[] frameId = new byte[ExportForMigration.EXPORT_ID_BYTES];
                plaintext.get(frameId);
                assertArrayEquals(id, frameId);
                assertEquals(kind, plaintext.get());
                assertEquals(index, plaintext.getLong());
                last = plaintext.get() == 1;
                content.write(plaintext.array(), plaintext.position(), plaintext.remaining());
            }
            assertEquals(-1, in.read());
        }
        return content.toByteArray();
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeStore(Path store) throws IOException {
        Files.writeString(store.resolve("encryption_key"), KEY + "\n");
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, store.resolve("encryption_key").toString());

        PhenoCube<Double> numeric = new PhenoCube<>(NUMERIC, Double.class);
        numeric.setColumnWidth(8);
        numeric.setSortedByKey(
            observations(
                new KeyAndValue<>(1, 2.5, null), new KeyAndValue<>(1, -0.0, Long.MIN_VALUE),
                new KeyAndValue<>(7, Double.longBitsToDouble(0x7ff8000000000123L), 1_700_000_000_000L)
            )
        );
        PhenoCube<String> text = new PhenoCube<>(TEXT, String.class);
        text.setColumnWidth(23);
        text.setSortedByKey(observations(new KeyAndValue<>(-4, AWKWARD, null), new KeyAndValue<>(9, "B", -5L)));
        text.setCategoryMap(categoryMap(text));
        PhenoCube<String> empty = new PhenoCube<>(EMPTY, String.class);
        empty.setSortedByKey(observations());

        TreeMap<String, ColumnMeta> metastore = new TreeMap<>();
        try (RandomAccessFile file = new RandomAccessFile(store.resolve("allObservationsStore.javabin").toFile(), "rw")) {
            ColumnMeta numericMeta =
                append(file, numeric).setWidthInBytes(8).setColumnOffset(3).setCategorical(false).setObservationCount(3).setPatientCount(2)
                    .setMin(-0.0).setMax(Double.MIN_VALUE).setHasTimestamp(true).setTimestampMin(5L).setTimestampMax(6L);
            metastore.put(NUMERIC, numericMeta);
            ColumnMeta textMeta = append(file, text).setWidthInBytes(23).setCategorical(true).setObservationCount(2).setPatientCount(2);
            textMeta.setCategoryValues(new ArrayList<>(List.of("B", AWKWARD)));
            metastore.put(TEXT, textMeta);
            ColumnMeta emptyMeta = append(file, empty).setCategorical(true);
            emptyMeta.setCategoryValues(new ArrayList<>());
            metastore.put(EMPTY, emptyMeta);
        }
        writeMetastore(store, metastore, List.of(9, 1, -4, 7));
    }

    private static void writeMetastore(Path store, TreeMap<String, ColumnMeta> metastore, List<Integer> patients) throws IOException {
        try (
            ObjectOutputStream out =
                new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(store.resolve("columnMeta.javabin"))))
        ) {
            out.writeObject(metastore);
            out.writeObject(new TreeSet<>(patients));
        }
    }

    @SafeVarargs
    private static <V extends Comparable<V>> KeyAndValue<V>[] observations(KeyAndValue<V>... observations) {
        return observations;
    }

    private static ColumnMeta append(RandomAccessFile file, PhenoCube<?> cube) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(cube);
        }
        ColumnMeta meta = new ColumnMeta().setName(cube.name);
        meta.setAllObservationsOffset(file.getFilePointer());
        file.write(Crypto.encryptData(bytes.toByteArray()));
        meta.setAllObservationsLength(file.getFilePointer());
        return meta;
    }
}
