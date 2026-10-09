package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertEquals("-4\n1\n7\n9\n", Files.readString(export.resolve(ExportForMigration.PATIENTS_FILE)));

        try (DataInputStream in = new DataInputStream(Files.newInputStream(export.resolve(ExportForMigration.CONCEPTS_FILE)))) {
            assertArrayEquals(ExportForMigration.MAGIC, in.readNBytes(8));
            assertEquals(ExportForMigration.VERSION, in.readInt());

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
    void refusesToExportIntoTheStoreDirectory(@TempDir Path dir) throws Exception {
        writeStore(dir);
        assertThrows(IllegalStateException.class, () -> new ExportForMigration(dir, dir).export());
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeStore(Path store) throws IOException {
        Files.writeString(store.resolve("encryption_key"), "0123456789ABCDEF0123456789ABCDEF\n");
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
        try (
            ObjectOutputStream out =
                new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(store.resolve("columnMeta.javabin"))))
        ) {
            out.writeObject(metastore);
            out.writeObject(new TreeSet<>(List.of(9, 1, -4, 7)));
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
