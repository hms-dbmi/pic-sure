package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Test helper that reads a store directory the way the HPDS service does: columnMeta.javabin for the index, then a seek into
 * allObservationsStore.javabin and a decrypt with the DEFAULT key.
 */
final class StoreFixture {

    /** Same 16 byte AES key as the other etl test resources. */
    private static final String KEY = "1111111111111111";

    private StoreFixture() {}

    /** Loads the test key under {@link Crypto#DEFAULT_KEY_NAME}, the name the HPDS service decrypts with. */
    static void loadDefaultKey(Path tempDir) throws IOException {
        Path keyFile = tempDir.resolve("encryption_key");
        Files.writeString(keyFile, KEY);
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, keyFile.toString());
    }

    @SuppressWarnings("unchecked")
    static TreeMap<String, ColumnMeta> readColumnMeta(Path storeDir) throws IOException, ClassNotFoundException {
        try (
            ObjectInputStream in =
                new ObjectInputStream(new GZIPInputStream(new FileInputStream(storeDir.resolve("columnMeta.javabin").toFile())))
        ) {
            return (TreeMap<String, ColumnMeta>) in.readObject();
        }
    }

    @SuppressWarnings("unchecked")
    static TreeSet<Integer> readAllIds(Path storeDir) throws IOException, ClassNotFoundException {
        try (
            ObjectInputStream in =
                new ObjectInputStream(new GZIPInputStream(new FileInputStream(storeDir.resolve("columnMeta.javabin").toFile())))
        ) {
            in.readObject();
            return (TreeSet<Integer>) in.readObject();
        }
    }

    static PhenoCube<?> readCube(Path storeDir, ColumnMeta meta) throws IOException, ClassNotFoundException {
        try (RandomAccessFile store = new RandomAccessFile(storeDir.resolve("allObservationsStore.javabin").toFile(), "r")) {
            store.seek(meta.getAllObservationsOffset());
            byte[] buffer = new byte[(int) (meta.getAllObservationsLength() - meta.getAllObservationsOffset())];
            store.readFully(buffer);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Crypto.decryptData(buffer)))) {
                return (PhenoCube<?>) in.readObject();
            }
        }
    }

    static PhenoCube<?> readCube(Path storeDir, String conceptPath) throws IOException, ClassNotFoundException {
        return readCube(storeDir, readColumnMeta(storeDir).get(conceptPath));
    }
}
