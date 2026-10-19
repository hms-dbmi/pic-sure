package edu.harvard.hms.dbmi.avillach.hpds.data.genotype.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;

import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariableVariantMasks;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskBitmaskImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantMaskSparseImpl;
import edu.harvard.hms.dbmi.avillach.hpds.data.genotype.VariantStore;
import edu.harvard.hms.dbmi.avillach.hpds.data.storage.FileBackedStorageVariantMasksImpl;
import edu.harvard.hms.dbmi.avillach.hpds.storage.FileBackedJsonIndexStorage;

class ExportGenomicForMigrationTest {

    private static final String KEY = "0123456789ABCDEF0123456789ABCDEF";

    /** A mask storage of another class than the current one, standing in for the legacy format. */
    static final class OtherMaskStorage extends FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> {

        private static final long serialVersionUID = 1L;

        OtherMaskStorage(File file) throws FileNotFoundException {
            super(file);
        }

        @Override
        public TypeReference<ConcurrentHashMap<String, VariableVariantMasks>> getTypeReference() {
            return new TypeReference<>() {};
        }
    }

    @Test
    void bitmaskPositionsDropThePaddingAndKeepPatientOrder() {
        BigInteger mask = new BigInteger("11" + new StringBuilder("1010000001").reverse() + "11", 2);
        byte[] bitmap = ExportGenomicForMigration.bitmaskPositions(mask, 10, "test");
        assertArrayEquals(new byte[] {0b0000_0101, 0b0000_0010}, bitmap);
    }

    @Test
    void bitmaskOfAnotherLengthIsRefused() {
        BigInteger mask = new BigInteger("11" + "0000000001" + "11", 2);
        assertThrows(IllegalStateException.class, () -> ExportGenomicForMigration.bitmaskPositions(mask, 11, "test"));
        BigInteger unpadded = new BigInteger("11" + "0000000001" + "01", 2);
        assertThrows(IllegalStateException.class, () -> ExportGenomicForMigration.bitmaskPositions(unpadded, 10, "test"));
    }

    @Test
    void placementFollowsTheQueryServiceLookup() {
        assertEquals(new ExportGenomicForMigration.Placement("chr1", 12), ExportGenomicForMigration.place("chr1,12345,A,T,G,c"));
        assertEquals(new ExportGenomicForMigration.Placement("chr1", 0), ExportGenomicForMigration.place("chr1,0100,A,T"));
        assertEquals(new ExportGenomicForMigration.Placement("chr1", -1), ExportGenomicForMigration.place("chr1,-1000,A,T"));
        assertNull(ExportGenomicForMigration.place("chr1"));
        assertNull(ExportGenomicForMigration.place("chr1,12x,A,T"));
    }

    @Test
    void unpairedSurrogatesAreRefusedWithTheirLocation() throws IOException {
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream());
        ExportGenomicForMigration.writeString(out, "ok 😀", "here");
        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> ExportGenomicForMigration.writeString(out, "bad \uD83D", "node chr1 key x"));
        assertTrue(e.getMessage().startsWith("node chr1 key x"), e.getMessage());
    }

    @Test
    void layoutsListNodesInJavaOrderAndRefuseDirectoriesThatAreNotNodes(@TempDir Path root) throws IOException {
        for (String group : List.of("b", "a")) {
            for (String chromosome : List.of("chr2", "chr10")) {
                Path node = Files.createDirectories(root.resolve(group).resolve(chromosome));
                Files.writeString(node.resolve(VariantStore.VARIANT_STORE_JAVABIN_FILENAME), "");
            }
        }
        List<ExportGenomicForMigration.Node> nodes = ExportGenomicForMigration.layout("localPatientDistributed", root);
        assertEquals(
            List.of("a/chr10", "a/chr2", "b/chr10", "b/chr2"), nodes.stream().map(n -> n.group() + "/" + n.dir()).toList()
        );
        Files.createDirectories(root.resolve("a").resolve("notes"));
        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> ExportGenomicForMigration.layout("localPatientDistributed", root));
        assertTrue(e.getMessage().contains("notes"), e.getMessage());
    }

    @Test
    void tooManyGroupsAreRefused(@TempDir Path root) throws IOException {
        for (int g = 0; g <= ExportGenomicForMigration.MAX_GROUPS; g++) {
            Files.createDirectories(root.resolve("g" + g));
        }
        assertThrows(IllegalStateException.class, () -> ExportGenomicForMigration.layout("localPatientOnlyDistributed", root));
    }

    @Test
    void theLegacyMaskFormatIsRefusedByClassName(@TempDir Path dir) throws Exception {
        Path store = Files.createDirectories(dir.resolve("store"));
        writeStore(store, new OtherMaskStorage(store.resolve("chr1masks.bin").toFile()));
        Path key = dir.resolve("key");
        Files.writeString(key, KEY);
        IllegalStateException e = assertThrows(
            IllegalStateException.class, () -> new ExportGenomicForMigration("local", store, key, dir.resolve("export")).export()
        );
        assertTrue(e.getMessage().contains("legacy VariantMasks"), e.getMessage());
        assertTrue(e.getMessage().contains(OtherMaskStorage.class.getName()), e.getMessage());
    }

    @Test
    void aStoreExportsWithoutWritingToIt(@TempDir Path dir) throws Exception {
        Path store = Files.createDirectories(dir.resolve("store"));
        FileBackedStorageVariantMasksImpl masks = new FileBackedStorageVariantMasksImpl(store.resolve("chr1masks.bin").toFile());
        VariableVariantMasks record = new VariableVariantMasks();
        record.heterozygousMask = new VariantMaskSparseImpl(Set.of(0, 2));
        record.homozygousMask = new VariantMaskBitmaskImpl(new BigInteger("11" + "1111110" + "11", 2));
        ConcurrentHashMap<String, VariableVariantMasks> bucket = new ConcurrentHashMap<>(Map.of("chr1,1500,A,T,G,c", record));
        masks.put(1, bucket);
        masks.complete();
        writeStore(store, masks);
        Path key = dir.resolve("key");
        Files.writeString(key, KEY);
        Map<String, Long> before = modified(store);

        ExportGenomicForMigration.Summary summary =
            new ExportGenomicForMigration("local", store, key, dir.resolve("export")).export();

        assertEquals(new ExportGenomicForMigration.Summary(1, 1, 1), summary);
        assertTrue(Files.isRegularFile(dir.resolve("export").resolve(ExportGenomicForMigration.MANIFEST_FILE)));
        assertTrue(Files.isRegularFile(dir.resolve("export").resolve("node-00000.bin")));
        assertEquals(before, modified(store));
    }

    private static void writeStore(Path store, FileBackedJsonIndexStorage<Integer, ConcurrentHashMap<String, VariableVariantMasks>> masks) {
        VariantStore variantStore = new VariantStore();
        variantStore.setPatientIds(new String[] {"10", "11", "12", "13", "14", "15", "16"});
        variantStore.setVariantMaskStorage(new TreeMap<>(Map.of("chr1", masks)));
        variantStore.setVariantSpecIndex(new String[] {"chr1,1500,A,T,G,c"});
        variantStore.writeInstance(store.toString());
    }

    private static Map<String, Long> modified(Path store) throws IOException {
        Map<String, Long> times = new TreeMap<>();
        try (var files = Files.list(store)) {
            for (Path file : files.toList()) {
                times.put(file.getFileName().toString(), Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file));
            }
        }
        return times;
    }
}
