package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.csv.CSVLoader;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.StoreFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity mini-harness: the same small fixture loaded with the legacy {@code CSVLoader} (on input sorted the way the shared runner sorts it,
 * {@code LC_ALL=C sort -t, -k2}) and with {@code PICSURELoader} (on the same rows in a shuffled order) gives the same concepts, with the
 * same type, observation count, patient count, category values, min and max, and the same value for every patient.
 *
 * <p>Differences that are intended, and not part of the comparison (each is also covered by a test of its own): <ul> <li><b>Timestamp
 * {@code 0}</b>: PICSURELoader stores no timestamp (the T10 D-TS decision, also what a blank cell means); CSVLoader stores {@code Date(0)},
 * 1970-01-01 (asserted in {@link #timestampZeroIsNullHereAndEpochInCsvLoader}). Other epoch-millisecond timestamps are identical. ISO and
 * zoneless timestamps are parsed by PICSURELoader; CSVLoader would stop with a number format error.</li> <li><b>Max of an all-negative
 * numeric concept</b>: CSVLoader seeds its max with {@code Double.MIN_VALUE} (the smallest positive double), so it reports 4.9E-324;
 * PICSURELoader reports the real maximum (asserted in {@link #maxOfAnAllNegativeConceptIsRealHere}).</li> <li><b>Rows without any
 * value</b>: CSVLoader skips them silently; PICSURELoader records MISSING_VALUE in failures.jsonl and in the summary. The stores are the
 * same.</li> <li><b>Text in a numeric concept</b>: CSVLoader decides the type by the first row it sees for the concept and silently drops
 * rows of the other kind; PICSURELoader promotes the concept to categorical whatever the order (and the sentinel rule below).</li>
 * <li><b>Null sentinels</b> ("NA", "None", ...): CSVLoader stores them as ordinary text; PICSURELoader drops them from numeric concepts and
 * never types a concept by them. A text value {@code NaN} is a number {@code NaN} in CSVLoader and a sentinel here.</li> <li><b>U+FFFD in a
 * text value</b>: removed by PICSURELoader (the path rule of CSVLoader applied to the value), kept in the value by CSVLoader. The fixture
 * has none.</li> <li><b>A blank concept path</b>: rejected by PICSURELoader; CSVLoader would load it as the root {@code \}.</li>
 * <li><b>Exact duplicate rows</b> are kept by both (dedup is off by default).</li> </ul> Test accessions only: phs000000, phs999999,
 * phs123456.
 */
class CsvLoaderParityTest {

    private static final String KEY = "1111111111111111";

    private static final List<String> FIXTURE = List.of(
        // numeric, an exact duplicate, a numeric-looking text value, both values, a zero timestamp, a row without a value
        "1,\\phs000000\\Demographics\\Age\\,40,,1700000000000", "2,\\phs000000\\Demographics\\Age\\,55,,",
        "3,\\phs000000\\Demographics\\Age\\,31,,0", "1,\\phs000000\\Demographics\\Age\\,40,,1700000000000",
        "4,\\phs000000\\Demographics\\Age\\,,62,", "5,\\phs000000\\Demographics\\Age\\,18.5,ignored,",
        "6,\\phs000000\\Demographics\\Age\\,,,", "7,\\phs000000\\Demographics\\Age\\,,\" 47 \",",
        // categorical, with a comma and a non-ASCII character in a category
        "1,\\phs000000\\Demographics\\Sex\\,,male,", "2,\\phs000000\\Demographics\\Sex\\,,female,",
        "3,\\phs000000\\Demographics\\Sex\\,,female,", "4,\\phs000000\\Demographics\\Sex\\,,\"female, other\",",
        "5,\\phs000000\\Demographics\\Sex\\,,naïve,",
        // µ as the path delimiter
        "1,µphs000000µLabsµGlucoseµ,5.5,,", "2,µphs000000µLabsµGlucoseµ,7.25,,1600000000000",
        // text equal to the last path segment is rolled up into the parent concept
        "1,\\phs000000\\Smoking\\Yes\\,,Yes,", "2,\\phs000000\\Smoking\\No\\,,No,", "3,\\phs000000\\Smoking\\,,Never,",
        // segments are trimmed, text is trimmed, U+FFFD is removed from the path
        "1,\"\\phs000000\\ Padded \\Thing\\ \",,  hello  ,", "2,\\phs000000\\Wei�rd\\,3,,",
        // all-negative numeric concept
        "1,\\phs000000\\Neg\\Temp\\,-4.5,,", "2,\\phs000000\\Neg\\Temp\\,-1.25,,"
    );

    @TempDir
    Path tempDir;

    private Path legacyOut;
    private Path picsureOut;

    /** {@code LC_ALL=C sort -t, -k2}: by everything from the first comma on. */
    private static List<String> sortedLikeTheRunner(List<String> lines) {
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing((String line) -> line.substring(line.indexOf(','))));
        return sorted;
    }

    private void runBoth() throws IOException {
        Path legacy = tempDir.resolve("legacy");
        Path picsure = tempDir.resolve("picsure");
        for (Path base : List.of(legacy, picsure)) {
            Files.createDirectories(base);
            Files.writeString(base.resolve("encryption_key"), KEY);
        }
        // "parity" has no hyphen, so the legacy loader's partition name (text before the first '-') is the same
        Files.writeString(legacy.resolve("parity-allConcepts.csv"), String.join("\n", sortedLikeTheRunner(FIXTURE)) + "\n");
        List<String> shuffled = new ArrayList<>(FIXTURE);
        Collections.shuffle(shuffled, new Random(11));
        Files.writeString(picsure.resolve("parity-allConcepts.csv"), String.join("\n", shuffled) + "\n");

        CSVLoader.main(new String[] {legacy.toString()});
        assertEquals(0, PICSURELoader.run(new String[] {picsure.toString()}, key -> null));
        legacyOut = legacy.resolve("parity");
        picsureOut = picsure.resolve("parity");
    }

    private record Described(
        boolean categorical, long observations, int patients, List<String> categories, Double min, Double max, int width,
        List<String> values
    ) {
    }

    private static TreeMap<String, Described> describe(Path dir) throws Exception {
        TreeMap<String, Described> concepts = new TreeMap<>();
        for (var entry : StoreFixture.readColumnMeta(dir).entrySet()) {
            ColumnMeta meta = entry.getValue();
            PhenoCube<?> cube = StoreFixture.readCube(dir, meta);
            List<String> values = new ArrayList<>();
            for (KeyAndValue<?> kv : cube.sortedByKey()) {
                values.add(kv.getKey() + "=" + kv.getValue());
            }
            Collections.sort(values);
            concepts.put(
                entry.getKey(),
                new Described(
                    meta.isCategorical(), meta.getObservationCount(), meta.getPatientCount(), meta.getCategoryValues(), meta.getMin(),
                    meta.getMax(), meta.getWidthInBytes(), values
                )
            );
        }
        return concepts;
    }

    @Test
    void bothLoadersProduceTheSameConceptsAndValues() throws Exception {
        runBoth();

        TreeMap<String, Described> legacy = describe(legacyOut);
        TreeMap<String, Described> picsure = describe(picsureOut);

        assertEquals(
            List.of(
                "\\phs000000\\Demographics\\Age\\", "\\phs000000\\Demographics\\Sex\\", "\\phs000000\\Labs\\Glucose\\",
                "\\phs000000\\Neg\\Temp\\", "\\phs000000\\Padded\\Thing\\", "\\phs000000\\Smoking\\", "\\phs000000\\Weird\\"
            ), List.copyOf(picsure.keySet())
        );
        assertEquals(legacy.keySet(), picsure.keySet(), "same concepts");
        for (String concept : legacy.keySet()) {
            Described expected = legacy.get(concept);
            Described actual = picsure.get(concept);
            if (concept.equals("\\phs000000\\Neg\\Temp\\")) {
                // CSVLoader's max of an all-negative concept is wrong (see the class comment): compare everything else
                expected = new Described(
                    expected.categorical(), expected.observations(), expected.patients(), expected.categories(), expected.min(),
                    actual.max(), expected.width(), expected.values()
                );
            }
            assertEquals(expected, actual, concept);
        }
        assertEquals(StoreFixture.readAllIds(legacyOut), StoreFixture.readAllIds(picsureOut), "same patients");

        // spot checks of what the comparison covers
        Described age = picsure.get("\\phs000000\\Demographics\\Age\\");
        assertFalse(age.categorical());
        assertEquals(
            7, age.observations(), "the exact duplicate is kept, numeric-looking text is numeric, a row without a value is not stored"
        );
        assertEquals(18.5, age.min());
        assertEquals(62.0, age.max());
        Described smoking = picsure.get("\\phs000000\\Smoking\\");
        assertEquals(List.of("Never", "No", "Yes"), smoking.categories());
        assertEquals(List.of("female", "female, other", "male", "naïve"), picsure.get("\\phs000000\\Demographics\\Sex\\").categories());
    }

    @Test
    void columnMetaCsvHasTheSameColumnsAsTheLegacyLoaderWrites() throws Exception {
        runBoth();

        List<org.apache.commons.csv.CSVRecord> legacy = readCsv(legacyOut.resolve("columnMeta.csv"));
        List<org.apache.commons.csv.CSVRecord> picsure = readCsv(picsureOut.resolve("columnMeta.csv"));

        assertEquals(legacy.size(), picsure.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(11, picsure.get(i).size());
            assertEquals(legacy.get(i).size(), picsure.get(i).size());
            // name, width, column offset, categorical, categories, min, max: identical; offsets differ (byte positions), counts are
            // identical
            for (int column : new int[] {0, 1, 2, 3, 4, 9, 10}) {
                assertEquals(legacy.get(i).get(column), picsure.get(i).get(column), "row " + i + " column " + column);
            }
            if (!legacy.get(i).get(0).equals("\\phs000000\\Neg\\Temp\\")) {
                assertEquals(legacy.get(i).get(5), picsure.get(i).get(5), "min, row " + i);
                assertEquals(legacy.get(i).get(6), picsure.get(i).get(6), "max, row " + i);
            }
        }
    }

    private static List<org.apache.commons.csv.CSVRecord> readCsv(Path file) throws IOException {
        try (var parser = org.apache.commons.csv.CSVFormat.DEFAULT.parse(Files.newBufferedReader(file))) {
            return parser.getRecords();
        }
    }

    @Test
    void timestampZeroIsNullHereAndEpochInCsvLoader() throws Exception {
        runBoth();

        Map<Integer, Long> legacy = timestamps(legacyOut, "\\phs000000\\Demographics\\Age\\");
        Map<Integer, Long> picsure = timestamps(picsureOut, "\\phs000000\\Demographics\\Age\\");

        assertEquals(0L, legacy.get(3), "CSVLoader: timestamp 0 is Date(0)");
        assertNull(picsure.get(3), "PICSURELoader: timestamp 0 means none");
        assertEquals(1700000000000L, legacy.get(1));
        assertEquals(1700000000000L, picsure.get(1));
        assertNull(legacy.get(2));
        assertNull(picsure.get(2));
    }

    private static Map<Integer, Long> timestamps(Path dir, String concept) throws Exception {
        Map<Integer, Long> result = new TreeMap<>();
        for (KeyAndValue<?> kv : StoreFixture.readCube(dir, concept).sortedByKey()) {
            result.put(kv.getKey(), kv.getTimestamp());
        }
        return result;
    }

    @Test
    void maxOfAnAllNegativeConceptIsRealHere() throws Exception {
        runBoth();

        assertEquals(Double.MIN_VALUE, StoreFixture.readColumnMeta(legacyOut).get("\\phs000000\\Neg\\Temp\\").getMax());
        assertEquals(-1.25, StoreFixture.readColumnMeta(picsureOut).get("\\phs000000\\Neg\\Temp\\").getMax());
        assertEquals(-4.5, StoreFixture.readColumnMeta(picsureOut).get("\\phs000000\\Neg\\Temp\\").getMin());
    }
}
