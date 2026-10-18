package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step-1 review issue 2 and decision 2: the null-sentinel check runs before a concept's type is decided, so the stored concept does not
 * depend on the order of the rows.
 */
class SpoolingLoadingStoreOrderTest {

    private static final String A = "\\phs000000\\Study\\A\\";
    private static final String B = "\\phs000000\\Study\\B\\";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        StoreFixture.loadDefaultKey(tempDir);
    }

    private record Row(int patient, String concept, Comparable<?> value) {
    }

    private SpoolingLoadingStore store(Path out, int cacheSize) {
        return new SpoolingLoadingStore(
            tempDir.resolve("spool-" + out.getFileName()), out, Crypto.DEFAULT_KEY_NAME, cacheSize, 0, 2, 10, false
        );
    }

    /** Loads rows in the given order and returns a canonical description of every concept written. */
    private TreeMap<String, String> load(List<Row> rows, int cacheSize, String name) throws Exception {
        Path out = tempDir.resolve(name);
        SpoolingLoadingStore store = store(out, cacheSize);
        for (Row row : rows) {
            store.addObservation(row.patient(), row.concept(), row.value(), null);
        }
        store.saveStore();
        assertTrue(store.getFailedConcepts().isEmpty());
        TreeMap<String, String> described = new TreeMap<>();
        for (var entry : StoreFixture.readColumnMeta(out).entrySet()) {
            ColumnMeta meta = entry.getValue();
            PhenoCube<?> cube = StoreFixture.readCube(out, meta);
            List<String> values = new ArrayList<>();
            for (KeyAndValue<?> kv : cube.sortedByKey()) {
                values.add(kv.getKey() + "=" + kv.getValue());
            }
            Collections.sort(values);
            described.put(entry.getKey(), (meta.isCategorical() ? "cat " : "num ") + meta.getObservationCount() + " " + values);
        }
        TreeSet<Integer> ids = StoreFixture.readAllIds(out);
        described.put("<patients>", ids.toString());
        StoreStatsHolder.last = store.getStats();
        return described;
    }

    private static final class StoreStatsHolder {
        static SpoolingLoadingStore.StoreStats last;
    }

    @Test
    void aConceptOfOnlySentinelsIsWrittenAsACategoricalConceptLikeCsvLoader() throws Exception {
        SpoolingLoadingStore store = store(tempDir.resolve("out1"), 16);
        assertEquals(AddResult.HELD_NULL_SENTINEL, store.addObservation(1, A, "NA", null));
        assertEquals(AddResult.HELD_NULL_SENTINEL, store.addObservation(2, A, "None", null));
        store.addObservation(1, B, 1.0, null);
        store.saveStore();

        Path out = tempDir.resolve("out1");
        assertEquals(Set.of(A, B), StoreFixture.readColumnMeta(out).keySet());
        ColumnMeta sentinels = StoreFixture.readColumnMeta(out).get(A);
        assertTrue(sentinels.isCategorical());
        assertEquals(List.of("NA", "None"), sentinels.getCategoryValues());
        assertEquals(2, sentinels.getObservationCount());
        assertEquals(2, store.getConceptCount());
        assertEquals(Set.of(1, 2), StoreFixture.readAllIds(out), "the patients of the sentinels are patients of the store");
        SpoolingLoadingStore.StoreStats stats = store.getStats();
        assertEquals(3, stats.accepted());
        assertEquals(0, stats.skippedNullSentinel());
    }

    @Test
    void aConceptOfOnlySentinelsIsTheSameInAnyOrderAndInAnyCache() throws Exception {
        List<Row> rows = new ArrayList<>(
            List.of(new Row(1, A, "NA"), new Row(2, A, "none"), new Row(3, A, "NA"), new Row(1, B, 4.0), new Row(2, B, "N/A"))
        );
        TreeMap<String, String> expected = load(rows, 1, "expected");
        assertTrue(expected.get(A).startsWith("cat 3 "), expected.get(A));
        assertTrue(expected.get(B).startsWith("num 1 "), expected.get(B));

        Random random = new Random(3);
        for (int i = 0; i < 15; i++) {
            Collections.shuffle(rows, random);
            assertEquals(expected, load(rows, 1 + i % 2, "s" + i), "permutation " + rows);
        }
    }

    @Test
    void sentinelsBeforeTheFirstNumberAreDroppedLikeThoseAfterIt() throws Exception {
        TreeMap<String, String> before = load(
            List.of(new Row(1, A, "NA"), new Row(2, A, "None"), new Row(3, A, 5.0), new Row(4, A, 6.0), new Row(5, A, "NA")), 16, "before"
        );
        TreeMap<String, String> after = load(
            List.of(new Row(3, A, 5.0), new Row(4, A, 6.0), new Row(1, A, "NA"), new Row(2, A, "None"), new Row(5, A, "NA")), 16, "after"
        );

        assertEquals(before, after);
        assertTrue(before.get(A).startsWith("num 2 "), before.get(A));
        assertEquals(3, StoreStatsHolder.last.skippedNullSentinel());
        assertEquals("[3, 4]", before.get("<patients>"));
    }

    @Test
    void sentinelsAreCategoriesOfAConceptThatTurnsOutCategoricalWhateverTheOrder() throws Exception {
        TreeMap<String, String> before = load(List.of(new Row(1, A, "None"), new Row(2, A, "NA"), new Row(3, A, "yes")), 16, "before");
        TreeMap<String, String> after = load(List.of(new Row(3, A, "yes"), new Row(1, A, "None"), new Row(2, A, "NA")), 16, "after");

        assertEquals(before, after);
        assertTrue(before.get(A).startsWith("cat 3 "), before.get(A));
        assertEquals("[1, 2, 3]", before.get("<patients>"), "the patients of kept sentinels count");
        assertEquals(3, StoreStatsHolder.last.accepted());
        assertEquals(0, StoreStatsHolder.last.skippedNullSentinel());
    }

    @Test
    void anyPermutationOfMixedRowsGivesTheSameStore() throws Exception {
        List<Row> rows = new ArrayList<>(
            List.of(
                new Row(1, A, "NA"), new Row(2, A, 5.0), new Row(3, A, "NA"), new Row(4, A, 6.5), new Row(5, A, "nan"),
                new Row(1, B, "None"), new Row(2, B, "red"), new Row(3, B, 4.0), new Row(4, B, "N/A"), new Row(5, B, "blue")
            )
        );
        TreeMap<String, String> expected = load(rows, 1, "expected");
        // A is numeric, B is categorical (a number in a categorical concept is text "4.0", sentinels stay)
        assertTrue(expected.get(A).startsWith("num 2 "), expected.get(A));
        assertTrue(expected.get(B).startsWith("cat 5 "), expected.get(B));

        Random random = new Random(7);
        for (int i = 0; i < 25; i++) {
            Collections.shuffle(rows, random);
            for (int cacheSize : new int[] {1, 16}) {
                assertEquals(expected, load(rows, cacheSize, "p" + i + "-" + cacheSize), "permutation " + rows);
            }
        }
    }

    @Test
    void promotionToCategoricalIsTheSameInAnyOrder() throws Exception {
        TreeMap<String, String> numberFirst = load(List.of(new Row(1, A, 1.0), new Row(2, A, "high"), new Row(3, A, 2.5)), 1, "nf");
        TreeMap<String, String> textFirst = load(List.of(new Row(2, A, "high"), new Row(1, A, 1.0), new Row(3, A, 2.5)), 1, "tf");

        assertEquals(numberFirst, textFirst);
        assertTrue(numberFirst.get(A).contains("1=1.0") && numberFirst.get(A).contains("3=2.5"), numberFirst.get(A));
    }

    @Test
    void everyRowIsAccountedFor() throws Exception {
        Path out = tempDir.resolve("accounting");
        SpoolingLoadingStore store = new SpoolingLoadingStore(tempDir.resolve("sp"), out, Crypto.DEFAULT_KEY_NAME, 1, 3, 2, 10, true);
        int rows = 0;
        for (
            Row row : List.of(
                new Row(1, A, "NA"), new Row(2, A, 5.0), new Row(2, A, 5.0), new Row(3, A, "NA"), new Row(4, A, 6.0), new Row(5, A, 7.0),
                new Row(6, A, 8.0), new Row(1, B, "None"), new Row(2, B, "NA"), new Row(1, "\\C\\", 1.0), new Row(2, "\\C\\", "x")
            )
        ) {
            store.addObservation(row.patient(), row.concept(), row.value(), null);
            rows++;
        }
        store.saveStore();

        SpoolingLoadingStore.StoreStats stats = store.getStats();
        // A: 5.0 twice and 6.0 (the limit of 3), 7.0 and 8.0 refused, both sentinels dropped from the numeric concept; B: both sentinels
        // kept as a categorical concept; C: 1.0 and "x"
        assertEquals(rows, stats.accepted() + stats.skippedNullSentinel() + stats.rejectedConceptLimit());
        assertEquals(stats.accepted(), store.getObservationsWritten() + stats.duplicatesDropped() + store.getFailedConceptObservations());
        assertEquals(1, stats.duplicatesDropped());
        assertEquals(2, stats.skippedNullSentinel(), "the two sentinels of the numeric concept A");
        assertEquals(7, stats.accepted());
        assertEquals(2, stats.rejectedConceptLimit());
    }

    // ---- numbers in a categorical concept keep the text they were written as ---------------------------------------------------

    private static Comparable<?> number(String text) {
        return NumericText.encode(Double.parseDouble(text), text);
    }

    @Test
    void numbersInACategoricalConceptKeepTheirTextInAnyOrder() throws Exception {
        List<Row> rows = new ArrayList<>(
            List.of(
                new Row(1, A, number("007")), new Row(2, A, number("1")), new Row(3, A, number("1.50")), new Row(4, A, number("1e2")),
                new Row(5, A, number("2.5")), new Row(6, A, "yes"), new Row(7, A, "NA")
            )
        );
        TreeMap<String, String> expected = load(rows, 1, "expected");
        assertEquals("cat 7 [1=007, 2=1, 3=1.50, 4=1e2, 5=2.5, 6=yes, 7=NA]", expected.get(A));

        Random random = new Random(5);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(rows, random);
            assertEquals(expected, load(rows, 1 + i % 3, "n" + i), "permutation " + rows);
        }
    }

    @Test
    void theSameNumbersInANumericConceptAreNumbers() throws Exception {
        TreeMap<String, String> numeric = load(
            List.of(new Row(1, A, number("007")), new Row(2, A, number("1")), new Row(3, A, number("1.50")), new Row(4, A, number("1e2"))),
            1, "num"
        );

        assertTrue(numeric.get(A).startsWith("num 4 "), numeric.get(A));
        assertTrue(
            numeric.get(A).contains("1=7.0") && numeric.get(A).contains("2=1.0") && numeric.get(A).contains("4=100.0"), numeric.get(A)
        );
    }

    @Test
    void numericTextIsOnlyCarriedWhenItIsNotWhatTheNumberPrintsAs() {
        assertEquals(40.0, NumericText.encode(40.0, "40.0"));
        assertEquals(40L, NumericText.encode(40.0, "40"));
        assertEquals(40.0, NumericText.encode(40.0, null));
        assertInstanceOf(NumericText.class, NumericText.encode(7.0, "007"));
        assertInstanceOf(NumericText.class, NumericText.encode(1.5, "1.50"));
        assertInstanceOf(NumericText.class, NumericText.encode(-0.0, "-0"));
        assertEquals("007", NumericText.textOf(NumericText.encode(7.0, "007")));
        assertEquals("40", NumericText.textOf(NumericText.encode(40.0, "40")));
        assertEquals("1.5", NumericText.textOf(NumericText.encode(1.5, "1.5")));
        assertEquals(7.0, NumericText.doubleOf(NumericText.encode(7.0, "007")));
        assertEquals(40.0, NumericText.doubleOf(NumericText.encode(40.0, "40")));
    }

    // ---- kept sentinels count against the limit ------------------------------------------------------------------------------

    @Test
    void keptSentinelsCountAgainstTheObservationLimit() throws Exception {
        Path out = tempDir.resolve("limit");
        SpoolingLoadingStore store =
            new SpoolingLoadingStore(tempDir.resolve("limit-spool"), out, Crypto.DEFAULT_KEY_NAME, 4, 4, 2, 10, false);
        store.addObservation(1, A, "red", null);
        store.addObservation(2, A, "blue", null);
        for (int p = 3; p <= 8; p++) {
            store.addObservation(p, A, "NA", null); // six held sentinels, room for two
        }
        store.saveStore();

        assertEquals(4, StoreFixture.readColumnMeta(out).get(A).getObservationCount(), "the limit is respected");
        assertEquals(Map.of(A, 4L), store.getRejectedConcepts(), "and the refused sentinels are reported, which fails the partition");
        SpoolingLoadingStore.StoreStats stats = store.getStats();
        assertEquals(4, stats.accepted(), "two real values and the two sentinels there was room for");
        assertEquals(4, stats.rejectedConceptLimit());
        assertEquals(stats.accepted(), store.getObservationsWritten() + stats.duplicatesDropped());
    }

    @Test
    void droppedSentinelsOfANumericConceptDoNotCountAgainstTheLimit() throws Exception {
        Path out = tempDir.resolve("limit2");
        SpoolingLoadingStore store =
            new SpoolingLoadingStore(tempDir.resolve("limit2-spool"), out, Crypto.DEFAULT_KEY_NAME, 4, 2, 2, 10, false);
        store.addObservation(1, A, 1.0, null);
        store.addObservation(2, A, 2.0, null);
        for (int p = 3; p <= 8; p++) {
            store.addObservation(p, A, "NA", null);
        }
        store.saveStore();

        assertEquals(0, store.getRejectedObservationCount());
        assertEquals(2, store.getObservationsWritten());
    }

    // ---- close without saving, heap gate -------------------------------------------------------------------------------

    @Test
    void closingWithoutSavingWritesNothingAndRemovesTheSpool() throws Exception {
        Path out = tempDir.resolve("aborted");
        Path spool = tempDir.resolve("aborted-spool");
        HPDSWriterAdapter adapter = new HPDSWriterAdapter(spool, out, Crypto.DEFAULT_KEY_NAME, 1, 0, 2, 10);
        adapter.acceptBatch(List.of(new ObservationRow(1, A, 1.0, null, null), new ObservationRow(1, B, 2.0, null, null)));
        assertTrue(Files.walk(spool).anyMatch(Files::isRegularFile), "evicted concept is spooled");

        adapter.close();

        assertFalse(Files.exists(out.resolve("allObservationsStore.javabin")));
        assertFalse(Files.exists(out.resolve("columnMeta.javabin")));
        assertFalse(Files.exists(out.resolve("columnMeta.csv")));
        try (var files = Files.walk(spool)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void aTinyHeapBudgetStillFinalizesEveryConceptWithoutRunningThemTogether() throws Exception {
        Path out = tempDir.resolve("gate");
        // budget of 64 KB with 400 bytes per observation: any concept of more than ~160 observations runs alone
        SpoolingLoadingStore store = new SpoolingLoadingStore(
            tempDir.resolve("gate-spool"), out, Crypto.DEFAULT_KEY_NAME, 4, 0, 8, 3, false,
            SpoolingLoadingStore.DEFAULT_MAX_MERGED_OBSERVATIONS, SpoolingLoadingStore.DEFAULT_MAX_SERIALIZED_BYTES, 64 * 1024
        );
        for (int concept = 0; concept < 12; concept++) {
            for (int p = 1; p <= 500; p++) {
                store.addObservation(p, "\\phs000000\\Gate\\C" + concept + "\\", (double) p, null);
            }
        }
        store.saveStore();

        assertTrue(store.getFailedConcepts().isEmpty());
        assertEquals(12, StoreFixture.readColumnMeta(out).size());
        assertEquals(6000, store.getObservationsWritten());
    }

    @Test
    void limitZeroMeansUnlimited() throws Exception {
        SpoolingLoadingStore store = store(tempDir.resolve("unlimited"), 16);
        for (int p = 1; p <= 300; p++) {
            assertEquals(AddResult.ADDED, store.addObservation(p, A, (double) p, null));
        }
        store.saveStore();

        assertEquals(0, store.getRejectedObservationCount());
        assertEquals(300, store.getObservationsWritten());
    }
}
