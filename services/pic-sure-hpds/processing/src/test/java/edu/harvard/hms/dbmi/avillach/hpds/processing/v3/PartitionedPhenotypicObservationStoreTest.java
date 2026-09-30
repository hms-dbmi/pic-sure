package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.processing.MissingConsentsException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class PartitionedPhenotypicObservationStoreTest {

    private static final String CONCEPT = "\\demographics\\AGE\\";

    @BeforeAll
    public static void loadEncryptionKey(@TempDir Path keyDirectory) throws IOException {
        // PhenotypicObservationStore decrypts every cube it loads, so the fixtures below have to be encrypted with a known key.
        Path keyFile = keyDirectory.resolve("encryption_key");
        Files.writeString(keyFile, "0123456789abcdef", StandardCharsets.UTF_8);
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, keyFile.toString());
    }

    /**
     * Writes the columnMeta and observation store files that make a directory loadable as a phenotypic store, holding a single numeric
     * concept with one observation per given patient id.
     */
    private static void writeStore(Path directory, Set<Integer> patientIds) throws IOException {
        Files.createDirectories(directory);

        PhenoCube<Double> cube = new PhenoCube<>(CONCEPT, Double.class);
        cube.setColumnWidth(Double.BYTES);
        KeyAndValue<Double>[] entries =
            new TreeSet<>(patientIds).stream().map(id -> new KeyAndValue<>(id, (double) id)).toArray(KeyAndValue[]::new);
        cube.setSortedByKey(entries);

        byte[] serializedCube;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(cube);
            out.flush();
            serializedCube = Crypto.encryptData(bytes.toByteArray());
        }
        Files.write(directory.resolve("allObservationsStore.javabin"), serializedCube);

        ColumnMeta columnMeta =
            new ColumnMeta().setName(CONCEPT).setWidthInBytes(Double.BYTES).setCategorical(false).setAllObservationsOffset(0)
                .setAllObservationsLength(serializedCube.length).setObservationCount(patientIds.size()).setPatientCount(patientIds.size());
        TreeMap<String, ColumnMeta> metaStore = new TreeMap<>();
        metaStore.put(CONCEPT, columnMeta);
        try (
            ObjectOutputStream out =
                new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(directory.resolve("columnMeta.javabin"))))
        ) {
            out.writeObject(metaStore);
            out.writeObject(new TreeSet<>(patientIds));
        }
    }

    @Test
    public void servesLegacyUnpartitionedDirectoryWhenAuthorizationFilterIsDisabled(@TempDir Path dataDirectory) throws IOException {
        writeStore(dataDirectory, Set.of(1, 2, 3));

        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(dataDirectory.toString(), false);

        assertTrue(store.getMetaStore().containsKey(CONCEPT), "legacy top level store should be loaded");
        // A legacy directory has no per-consent partitions, so any consent sees the whole store.
        assertEquals(Set.of(1, 2, 3), store.getPatientIds(Set.of("phs000001.c1")));
        assertEquals(Set.of(1, 2, 3), store.getAllKeys(CONCEPT, Set.of("some.consent.matching.no.partition")));
        assertEquals(Set.of(1, 2, 3), store.getAllKeys(CONCEPT, Set.of("phs000001.c1", "phs000002.c2", "phs000003.c3")));
        assertDoesNotThrow(store::getCachedKeys);
    }

    @Test
    public void rejectsLegacyUnpartitionedDirectoryWhenAuthorizationFilterIsRequired(@TempDir Path dataDirectory) throws IOException {
        writeStore(dataDirectory, Set.of(1, 2, 3));

        // An unpartitioned store cannot be scoped by consent, so startup must fail rather than serve it.
        assertThrows(IllegalStateException.class, () -> new PartitionedPhenotypicObservationStore(dataDirectory.toString(), true));
    }

    @Test
    public void partitionedDirectoryStillScopesByConsent(@TempDir Path dataDirectory) throws IOException {
        writeStore(dataDirectory.resolve("phs000001.c1"), Set.of(1, 2));
        writeStore(dataDirectory.resolve("phs000002.c2"), Set.of(3, 4));

        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(dataDirectory.toString(), true);

        assertEquals(Set.of(1, 2), store.getPatientIds(Set.of("phs000001.c1")));
        assertEquals(Set.of(3, 4), store.getPatientIds(Set.of("phs000002.c2")));
        assertEquals(Set.of(1, 2, 3, 4), store.getPatientIds(Set.of("phs000001.c1", "phs000002.c2")));
        assertEquals(Set.of(), store.getPatientIds(Set.of("phs000003.c3")));
        assertEquals(Set.of(1, 2, 3, 4), store.getAllKeys(CONCEPT, Set.of("phs000001.c1", "phs000002.c2")));
    }

    @Test
    public void getAllKeysReturnsPatientsInMultiplePartitionsOnce(@TempDir Path dataDirectory) throws IOException {
        writeStore(dataDirectory.resolve("phs000001.c1"), Set.of(1, 2, 3));
        writeStore(dataDirectory.resolve("phs000002.c2"), Set.of(2, 3, 4));

        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(dataDirectory.toString(), true);

        assertEquals(Set.of(1, 2, 3, 4), store.getAllKeys(CONCEPT, Set.of("phs000001.c1", "phs000002.c2")));
    }

    @Test
    public void requiresConsentsWhenAuthorizationFilterIsRequired(@TempDir Path dataDirectory) throws IOException {
        writeStore(dataDirectory.resolve("phs000001.c1"), Set.of(1));
        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(dataDirectory.toString(), true);
        assertThrows(MissingConsentsException.class, () -> store.getPatientIds(Set.of()));
    }
}
