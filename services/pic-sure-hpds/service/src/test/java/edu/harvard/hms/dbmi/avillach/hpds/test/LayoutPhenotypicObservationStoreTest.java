package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutConcept;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutFormatException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.LayoutPhenotypicObservationStore;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout.NumericLayoutBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the Rust-built 1000 Genomes layout fixture and checks the reader's failure paths: a wrong key, a damaged block, and filters of the
 * wrong type.
 */
public class LayoutPhenotypicObservationStoreTest {

    private static final Path STORE = Path.of("src/test/resources/layout/1000genomes");

    private static final String AGE = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";

    private static final String SEX = "\\open_access-1000Genomes\\data\\SEX\\";

    @TempDir
    Path temp;

    @Test
    public void readsIndexAndBlocks() throws IOException {
        try (LayoutPhenotypicObservationStore store = open(STORE, STORE.resolve("encryption_key"))) {
            assertEquals(29, store.index().concepts().size());
            assertEquals(4978, store.getPatientIds().getCardinality());
            LayoutConcept age = store.concept(AGE).orElseThrow();
            NumericLayoutBlock block = (NumericLayoutBlock) store.getBlock(AGE).orElseThrow();
            assertEquals(age.observationCount(), block.size());
            for (int i = 1; i < block.size(); i++) {
                assertTrue(Double.compare(block.values().get(i - 1), block.values().get(i)) <= 0);
            }
            assertEquals(age.patientCount(), block.allPatients().getCardinality());
            assertEquals(block.allPatients(), store.getKeysForRange(AGE, null, null));
            assertTrue(store.getKeysForRange(AGE, age.max() + 1, null).isEmpty());
            assertTrue(store.getKeysForRange(AGE, 50.0, 40.0).isEmpty());
            assertTrue(store.getAllKeys("\\not a concept\\").isEmpty());
            assertTrue(store.getKeysForValues(SEX, List.of()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.getKeysForValues(AGE, Set.of("40")));
            assertThrows(IllegalArgumentException.class, () -> store.getKeysForRange(SEX, 1.0, 2.0));
        }
    }

    @Test
    public void refusesAWrongKey() throws IOException {
        Path key = temp.resolve("encryption_key");
        Files.writeString(key, "0123456789abcdef0123456789abcdef");
        assertThrows(LayoutFormatException.class, () -> open(STORE, key));
    }

    @Test
    public void refusesADamagedBlock() throws IOException {
        Path copy = temp.resolve("store");
        Files.createDirectories(copy);
        for (String name : List.of("pheno.index", "pheno.blocks", "encryption_key")) {
            Files.copy(STORE.resolve(name), copy.resolve(name));
        }
        long offset;
        try (LayoutPhenotypicObservationStore store = open(STORE, STORE.resolve("encryption_key"))) {
            LayoutConcept age = store.concept(AGE).orElseThrow();
            offset = age.blockOffset() + age.blockLength() / 2;
        }
        try (RandomAccessFile blocks = new RandomAccessFile(copy.resolve("pheno.blocks").toFile(), "rw")) {
            blocks.seek(offset);
            int original = blocks.read();
            blocks.seek(offset);
            blocks.write(original ^ 0xff);
        }
        try (LayoutPhenotypicObservationStore store = open(copy, copy.resolve("encryption_key"))) {
            assertThrows(LayoutFormatException.class, () -> store.getBlock(AGE));
            assertTrue(store.getBlock(SEX).isPresent());
        }
    }

    private static LayoutPhenotypicObservationStore open(Path directory, Path key) {
        return new LayoutPhenotypicObservationStore(directory, key, 16L << 20);
    }
}
