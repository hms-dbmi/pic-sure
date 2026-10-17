package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.SpoolingLoadingStore.AddResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SpoolingLoadingStoreTest {

    private static final String A = "\\phs000000\\Study\\A\\";
    private static final String B = "\\phs000000\\Study\\B\\";
    private static final String C = "\\phs000000\\Study\\C\\";

    @TempDir
    Path tempDir;

    private Path spool;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        StoreFixture.loadDefaultKey(tempDir);
        spool = tempDir.resolve("spool");
        out = tempDir.resolve("out");
    }

    private SpoolingLoadingStore store(int cacheSize, int maxObservationsPerConcept) {
        return new SpoolingLoadingStore(spool, out, Crypto.DEFAULT_KEY_NAME, cacheSize, maxObservationsPerConcept, 4, 10);
    }

    private static Date at(long epochMillis) {
        return new Date(epochMillis);
    }

    private static Map<Integer, List<Object>> valuesByPatient(PhenoCube<?> cube) {
        Map<Integer, List<Object>> result = new TreeMap<>();
        for (KeyAndValue<?> kv : cube.sortedByKey()) {
            result.computeIfAbsent(kv.getKey(), k -> new ArrayList<>()).add(kv.getValue());
        }
        return result;
    }

    private long spoolFileCount() throws IOException {
        try (Stream<Path> files = Files.walk(spool)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void conceptSeenTwiceNonContiguouslyIsWrittenOnceWithAllObservations() throws Exception {
        // cache of one concept: every switch between concepts evicts and spools the other
        SpoolingLoadingStore store = store(1, 1_000);
        store.addObservation(1, A, 1.5, null);
        store.addObservation(2, B, "x", null);
        store.addObservation(3, A, 2.5, null);
        store.addObservation(4, B, "y", null);
        store.addObservation(5, A, 3.5, null);
        store.addObservation(6, C, 9.0, null);
        assertTrue(spoolFileCount() >= 4, "evictions should have produced partial files");

        store.saveStore();

        TreeMap<String, ColumnMeta> metas = StoreFixture.readColumnMeta(out);
        assertEquals(Set.of(A, B, C), metas.keySet());
        assertEquals(3, metas.get(A).getObservationCount());
        assertEquals(3, metas.get(A).getPatientCount());
        assertEquals(2, metas.get(B).getObservationCount());
        assertEquals(Map.of(1, List.of(1.5), 3, List.of(2.5), 5, List.of(3.5)), valuesByPatient(StoreFixture.readCube(out, A)));
        assertEquals(Map.of(2, List.of("x"), 4, List.of("y")), valuesByPatient(StoreFixture.readCube(out, B)));
        assertEquals(new TreeSet<>(Set.of(1, 2, 3, 4, 5, 6)), StoreFixture.readAllIds(out));
        assertTrue(store.getFailedConcepts().isEmpty());
        assertEquals(0, spoolFileCount(), "spool files are removed after a successful save");
    }

    @Test
    void unsortedInputAcrossManyConceptsAndASmallCacheLosesNothing() throws Exception {
        int concepts = 40;
        int perConcept = 150;
        List<int[]> rows = new ArrayList<>();
        for (int c = 0; c < concepts; c++) {
            for (int p = 0; p < perConcept; p++) {
                rows.add(new int[] {c, p});
            }
        }
        Collections.shuffle(rows, new Random(42));

        SpoolingLoadingStore store = store(3, 1_000_000);
        for (int[] row : rows) {
            String concept = "\\phs000000\\Study\\C" + row[0] + "\\";
            if (row[0] % 2 == 0) {
                store.addObservation(row[1], concept, row[0] * 1000.0 + row[1], at(1_000L + row[1]));
            } else {
                store.addObservation(row[1], concept, "v" + (row[1] % 7), at(1_000L + row[1]));
            }
        }
        store.saveStore();

        TreeMap<String, ColumnMeta> metas = StoreFixture.readColumnMeta(out);
        assertEquals(concepts, metas.size());
        for (int c = 0; c < concepts; c++) {
            String concept = "\\phs000000\\Study\\C" + c + "\\";
            ColumnMeta meta = metas.get(concept);
            assertEquals(perConcept, meta.getObservationCount(), concept);
            assertEquals(perConcept, meta.getPatientCount(), concept);
            assertEquals(c % 2 == 1, meta.isCategorical(), concept);
            PhenoCube<?> cube = StoreFixture.readCube(out, meta);
            KeyAndValue<?>[] sorted = cube.sortedByKey();
            assertEquals(perConcept, sorted.length);
            for (int p = 0; p < perConcept; p++) {
                assertEquals(p, sorted[p].getKey(), "cube must be sorted by patient");
                Object expected = c % 2 == 0 ? (Object) (c * 1000.0 + p) : "v" + (p % 7);
                assertEquals(expected, sorted[p].getValue());
            }
        }
        assertEquals(perConcept, StoreFixture.readAllIds(out).size());
    }

    @Test
    void readBackCubesAnswerQueriesLikeTheServiceDoes() throws Exception {
        SpoolingLoadingStore store = store(1, 1_000);
        store.addObservation(1, A, 10.0, null);
        store.addObservation(2, B, "red", null);
        store.addObservation(2, A, 20.0, null);
        store.addObservation(3, B, "blue", null);
        store.addObservation(3, A, 30.0, null);
        store.addObservation(4, B, "red", null);
        store.saveStore();

        @SuppressWarnings("unchecked")
        PhenoCube<Double> numeric = (PhenoCube<Double>) StoreFixture.readCube(out, A);
        @SuppressWarnings("unchecked")
        PhenoCube<String> categorical = (PhenoCube<String>) StoreFixture.readCube(out, B);
        assertEquals(Set.of(2, 3), numeric.getKeysForRange(15.0, 35.0));
        assertEquals(Set.of(2, 4), categorical.getKeysForValue("red"));
        assertEquals(Set.of("blue", "red"), categorical.getCategoryMap().keySet());
    }

    @Test
    void duplicateObservationsAreDroppedEvenWhenTheyLandInDifferentPartials() throws Exception {
        SpoolingLoadingStore store = store(1, 1_000);
        store.addObservation(1, A, 5.0, at(100));
        store.addObservation(1, B, "z", null); // evicts A
        store.addObservation(1, A, 5.0, at(100)); // exact duplicate, lands in a later partial
        store.addObservation(1, A, 5.0, at(200)); // same value, other timestamp: kept
        store.addObservation(1, A, 6.0, at(100)); // other value, same timestamp: kept
        store.addObservation(1, A, 5.0, at(100)); // duplicate within the live cube
        store.saveStore();

        ColumnMeta meta = StoreFixture.readColumnMeta(out).get(A);
        assertEquals(3, meta.getObservationCount());
        List<Object> values = valuesByPatient(StoreFixture.readCube(out, A)).get(1);
        assertEquals(3, values.size());
        assertEquals(Set.of(5.0, 6.0), Set.copyOf(values));
    }

    @Test
    void numericAndCategoricalConceptsGetTheirOwnMetadata() throws Exception {
        SpoolingLoadingStore store = store(16, 1_000);
        store.addObservation(1, A, -4.0, at(2_000));
        store.addObservation(2, A, -1.5, at(1_000));
        store.addObservation(3, A, -9.0, null);
        store.addObservation(1, B, "bb", null);
        store.addObservation(2, B, "aé", null); // 3 bytes in UTF-8
        store.addObservation(3, B, "bb", null);
        store.saveStore();

        TreeMap<String, ColumnMeta> metas = StoreFixture.readColumnMeta(out);
        ColumnMeta numeric = metas.get(A);
        assertFalse(numeric.isCategorical());
        assertEquals(-9.0, numeric.getMin());
        assertEquals(-1.5, numeric.getMax(), "an all-negative concept must report its real max");
        assertNull(numeric.getCategoryValues());
        assertEquals(Double.BYTES, numeric.getWidthInBytes());
        assertTrue(numeric.hasTimestamp());
        assertEquals(1_000L, numeric.getTimestampMin());
        assertEquals(2_000L, numeric.getTimestampMax());

        ColumnMeta categorical = metas.get(B);
        assertTrue(categorical.isCategorical());
        assertEquals(List.of("aé", "bb"), categorical.getCategoryValues());
        assertEquals(3, categorical.getWidthInBytes());
        assertFalse(categorical.hasTimestamp());

        List<String> csv = Files.readAllLines(out.resolve("columnMeta.csv"));
        assertEquals(2, csv.size());
        assertTrue(csv.get(1).contains("aéµbb"));
    }

    @Test
    void valuesOfTheWrongTypeAreCoercedToTheConceptsType() throws Exception {
        SpoolingLoadingStore store = store(16, 1_000);
        assertEquals(AddResult.ADDED, store.addObservation(1, A, 1.0, null));
        assertEquals(AddResult.ADDED, store.addObservation(2, A, "2.5", null)); // numeric text into a numeric concept
        assertEquals(AddResult.SKIPPED_NULL_SENTINEL, store.addObservation(3, A, "NA", null));
        assertEquals(AddResult.SKIPPED_NULL_SENTINEL, store.addObservation(4, A, " None ", null));
        assertEquals(AddResult.ADDED, store.addObservation(1, B, "low", null));
        assertEquals(AddResult.ADDED, store.addObservation(2, B, 7.0, null)); // number into a categorical concept
        store.saveStore();

        TreeMap<String, ColumnMeta> metas = StoreFixture.readColumnMeta(out);
        assertFalse(metas.get(A).isCategorical());
        assertEquals(Map.of(1, List.of(1.0), 2, List.of(2.5)), valuesByPatient(StoreFixture.readCube(out, A)));
        assertEquals(Map.of(1, List.of("low"), 2, List.of("7.0")), valuesByPatient(StoreFixture.readCube(out, B)));
        assertEquals(new TreeSet<>(Set.of(1, 2)), StoreFixture.readAllIds(out), "skipped sentinels do not register their patient");
    }

    @Test
    void numericConceptPromotedToCategoricalKeepsEarlierSpooledNumbers() throws Exception {
        // cache of one: the numeric values for A are spooled before the text arrives
        SpoolingLoadingStore store = store(1, 1_000);
        store.addObservation(1, A, 1.0, null);
        store.addObservation(2, A, 2.0, null);
        store.addObservation(9, B, "other", null); // evicts A -> partial 0
        store.addObservation(3, A, 3.0, null);
        store.addObservation(9, B, "other2", null); // evicts A -> partial 1
        store.addObservation(4, A, "not a number", null); // promotes A to categorical
        store.addObservation(5, A, 5.0, null); // now stored as text
        store.saveStore();

        ColumnMeta meta = StoreFixture.readColumnMeta(out).get(A);
        assertTrue(meta.isCategorical());
        assertEquals(5, meta.getObservationCount());
        assertEquals(
            Map.of(1, List.of("1.0"), 2, List.of("2.0"), 3, List.of("3.0"), 4, List.of("not a number"), 5, List.of("5.0")),
            valuesByPatient(StoreFixture.readCube(out, A))
        );
        assertTrue(store.getFailedConcepts().isEmpty());
    }

    @Test
    void observationsBeyondTheConceptLimitAreRejectedAndReported() throws Exception {
        SpoolingLoadingStore store = store(16, 3);
        for (int p = 1; p <= 3; p++) {
            assertEquals(AddResult.ADDED, store.addObservation(p, A, (double) p, null));
        }
        assertEquals(AddResult.REJECTED_CONCEPT_LIMIT, store.addObservation(4, A, 4.0, null));
        assertEquals(AddResult.REJECTED_CONCEPT_LIMIT, store.addObservation(5, A, 5.0, null));
        assertEquals(AddResult.ADDED, store.addObservation(4, B, 4.0, null));
        store.saveStore();

        assertEquals(3, StoreFixture.readColumnMeta(out).get(A).getObservationCount());
        assertEquals(new TreeSet<>(Set.of(1, 2, 3, 4)), StoreFixture.readAllIds(out), "rejected observations do not register patients");
        assertEquals(2, store.getRejectedObservationCount());

        List<String> lines = Files.readAllLines(out.resolve("concept_rejections.jsonl"));
        assertEquals(1, lines.size());
        JsonNode report = new ObjectMapper().readTree(lines.get(0)); // concept paths contain backslashes: must still be valid JSON
        assertEquals(A, report.get("conceptPath").asText());
        assertEquals(3, report.get("observationsAccepted").asLong());
        assertEquals(2, report.get("observationsRejected").asLong());
    }

    @Test
    void conceptOverTheMergeLimitFailsButTheOthersAreStillWritten() throws Exception {
        // guard path of the 2 GB limit, with a tiny limit: A would have more observations than fit in one array
        SpoolingLoadingStore store = new SpoolingLoadingStore(
            spool, out, Crypto.DEFAULT_KEY_NAME, 16, 1_000_000, 2, 10, 3, SpoolingLoadingStore.DEFAULT_MAX_SERIALIZED_BYTES
        );
        for (int p = 1; p <= 4; p++) {
            store.addObservation(p, A, (double) p, null);
        }
        store.addObservation(1, B, 1.0, null);
        store.saveStore();

        assertEquals(Set.of(A), store.getFailedConcepts().keySet());
        assertTrue(store.getFailedConcepts().get(A).contains("more than the 3"));
        assertEquals(Set.of(B), StoreFixture.readColumnMeta(out).keySet());
    }

    @Test
    void conceptOverTheEncryptionSizeLimitFailsButTheOthersAreStillWritten() throws Exception {
        SpoolingLoadingStore store = new SpoolingLoadingStore(
            spool, out, Crypto.DEFAULT_KEY_NAME, 16, 1_000_000, 2, 10, SpoolingLoadingStore.DEFAULT_MAX_MERGED_OBSERVATIONS, 2_000
        );
        for (int p = 1; p <= 500; p++) {
            store.addObservation(p, A, (double) p, null);
        }
        store.addObservation(1, B, 1.0, null);
        store.saveStore();

        assertEquals(Set.of(A), store.getFailedConcepts().keySet());
        assertTrue(store.getFailedConcepts().get(A).contains("too large for encryption"));
        assertEquals(Set.of(B), StoreFixture.readColumnMeta(out).keySet());
    }

    @Test
    void aFailedSpoolSurfacesInsteadOfLosingObservationsSilently() throws Exception {
        SpoolingLoadingStore store = store(1, 1_000);
        store.addObservation(1, A, 1.0, null);
        // make the spool location unusable: a regular file where the spool directory was
        try (Stream<Path> files = Files.walk(spool)) {
            files.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.writeString(spool, "not a directory");

        store.addObservation(1, B, 1.0, null); // evicts A, whose spool fails inside Guava's listener
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.addObservation(2, B, 2.0, null));
        assertTrue(e.getMessage().contains("could not be spooled"));
        assertThrows(IOException.class, store::saveStore);
    }

    @Test
    void concurrentAddsWithEvictionsLoseNothing() throws Exception {
        int threads = 8;
        int concepts = 30;
        int perThread = 4_000;
        SpoolingLoadingStore store = store(3, 10_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Map<String, Integer>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                Random random = new Random(thread);
                Map<String, Integer> expected = new HashMap<>();
                for (int i = 0; i < perThread; i++) {
                    String concept = "\\phs000000\\Study\\C" + random.nextInt(concepts) + "\\";
                    // unique patient per (thread, i): no row may collapse as a duplicate
                    store.addObservation(thread * 100_000 + i, concept, (double) i, null);
                    expected.merge(concept, 1, Integer::sum);
                }
                return expected;
            }));
        }
        Map<String, Integer> expected = new HashMap<>();
        for (Future<Map<String, Integer>> future : futures) {
            future.get().forEach((k, v) -> expected.merge(k, v, Integer::sum));
        }
        pool.shutdown();
        store.saveStore();

        TreeMap<String, ColumnMeta> metas = StoreFixture.readColumnMeta(out);
        assertEquals(expected.keySet(), metas.keySet());
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            assertEquals(entry.getValue().longValue(), metas.get(entry.getKey()).getObservationCount(), entry.getKey());
            assertEquals(entry.getValue(), StoreFixture.readCube(out, entry.getKey()).sortedByKey().length);
        }
        assertEquals(threads * perThread, expected.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(store.getFailedConcepts().isEmpty());
    }

    @Test
    void emptyStoreWritesEmptyFiles() throws Exception {
        store(16, 1_000).saveStore();

        assertTrue(StoreFixture.readColumnMeta(out).isEmpty());
        assertTrue(StoreFixture.readAllIds(out).isEmpty());
        assertEquals(0, Files.size(out.resolve("allObservationsStore.javabin")));
        assertTrue(Files.exists(out.resolve("columnMeta.csv")));
        assertEquals(List.of(), Files.readAllLines(out.resolve("concept_rejections.jsonl")));
    }

    @Test
    void rerunTruncatesAStaleObservationStore() throws Exception {
        Files.createDirectories(out);
        Files.write(out.resolve("allObservationsStore.javabin"), new byte[10_000]);

        SpoolingLoadingStore store = store(16, 1_000);
        store.addObservation(1, A, 1.0, null);
        store.saveStore();

        ColumnMeta meta = StoreFixture.readColumnMeta(out).get(A);
        assertEquals(meta.getAllObservationsLength(), Files.size(out.resolve("allObservationsStore.javabin")));
    }

    @Test
    void missingEncryptionKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> new SpoolingLoadingStore(spool, out, "no-such-key", 16, 1_000, 2, 10));
    }

    @Test
    void rejectsValuesThatAreNeitherNumbersNorText() {
        SpoolingLoadingStore store = store(16, 1_000);
        assertThrows(IllegalArgumentException.class, () -> store.addObservation(1, A, 5, null));
        assertThrows(IllegalArgumentException.class, () -> store.addObservation(1, A, null, null));
    }

    @Test
    void conceptsAreOrderedByNameInTheMetadata() throws Exception {
        SpoolingLoadingStore store = store(16, 1_000);
        store.addObservation(1, C, 1.0, null);
        store.addObservation(1, A, 1.0, null);
        store.addObservation(1, B, 1.0, null);
        store.saveStore();

        assertEquals(List.of(A, B, C), new ArrayList<>(StoreFixture.readColumnMeta(out).keySet()));
        assertEquals(
            List.of(A, B, C),
            Files.readAllLines(out.resolve("columnMeta.csv")).stream().map(l -> l.split(",")[0]).collect(Collectors.toList())
        );
    }
}
