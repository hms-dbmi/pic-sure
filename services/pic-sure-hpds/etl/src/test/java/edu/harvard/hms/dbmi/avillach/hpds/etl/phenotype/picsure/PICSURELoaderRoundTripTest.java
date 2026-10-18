package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.processing.MissingConsentsException;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture partitions to stores to the reader the HPDS service uses ({@code PartitionedPhenotypicObservationStore}, which treats every
 * subdirectory of the data directory as a consent). {@code processing} is a test dependency of {@code etl} (it does not depend on
 * {@code etl}, so there is no cycle). Test accessions only: phs000000, phs999999, phs123456.
 */
class PICSURELoaderRoundTripTest {

    private static final String AGE = "\\phs000000\\Demographics\\Age\\";
    private static final String SEX = "\\phs000000\\Demographics\\Sex\\";
    private static final String BMI = "\\phs999999\\Measures\\BMI\\";

    private static final String C1 = "phs000000.c1";
    private static final String CAMP = "tutorial-biolincc_camp";
    private static final String C2 = "phs999999.c2";

    @TempDir
    Path tempDir;

    private static void writeShuffled(Path file, long seed, String... lines) throws IOException {
        List<String> rows = new ArrayList<>(List.of(lines));
        Collections.shuffle(rows, new Random(seed));
        Files.writeString(file, String.join("\n", rows) + "\n");
    }

    private PartitionedPhenotypicObservationStore load() throws IOException {
        Path base = tempDir.resolve("hpds");
        Files.createDirectories(base);
        Files.writeString(base.resolve("encryption_key"), "1111111111111111");
        writeShuffled(
            base.resolve(C1 + "-allConcepts.csv"), 1, "1," + AGE + ",30,,", "2," + AGE + ",40,,", "3," + AGE + ",50,,",
            "1," + SEX + ",,male,1700000000000", "2," + SEX + ",,female,", "3," + SEX + ",,male,"
        );
        writeShuffled(
            base.resolve(CAMP + "-allConcepts.csv"), 2, "11," + AGE + ",60,,", "12," + AGE + ",70,,",
            "11," + BMI + ",22.5,,2021-01-15T10:30:00Z"
        );
        writeShuffled(base.resolve(C2 + "-allConcepts.csv"), 3, "21," + SEX + ",,female,", "22," + SEX + ",,male,");
        assertEquals(0, PICSURELoader.run(new String[] {base.toString()}, key -> null));
        return new PartitionedPhenotypicObservationStore(base.toString(), true);
    }

    private static Map<Integer, Object> valuesByPatient(PhenoCube<?> cube) {
        Map<Integer, Object> values = new TreeMap<>();
        for (KeyAndValue<?> kv : cube.sortedByKey()) {
            values.put(kv.getKey(), kv.getValue());
        }
        return values;
    }

    @Test
    void eachConsentReturnsItsOwnCubesAndPatients() throws IOException {
        PartitionedPhenotypicObservationStore store = load();

        assertEquals(Set.of(1, 2, 3), store.getPatientIds(Set.of(C1)));
        assertEquals(Set.of(11, 12), store.getPatientIds(Set.of(CAMP)), "the hyphenated consent is its own partition");
        assertEquals(Set.of(21, 22), store.getPatientIds(Set.of(C2)));
        assertEquals(Set.of(1, 2, 3, 11, 12), store.getPatientIds(Set.of(C1, CAMP)));

        assertEquals(Map.of(1, 30.0, 2, 40.0, 3, 50.0), valuesByPatient(store.getCube(AGE, Set.of(C1)).orElseThrow()));
        assertEquals(Map.of(11, 60.0, 12, 70.0), valuesByPatient(store.getCube(AGE, Set.of(CAMP)).orElseThrow()));
        assertEquals(Map.of(1, "male", 2, "female", 3, "male"), valuesByPatient(store.getCube(SEX, Set.of(C1)).orElseThrow()));
        assertEquals(Map.of(11, 22.5), valuesByPatient(store.getCube(BMI, Set.of(CAMP)).orElseThrow()));
    }

    @Test
    void aConceptIsOnlyVisibleThroughTheConsentsThatHoldIt() throws IOException {
        PartitionedPhenotypicObservationStore store = load();

        assertTrue(store.getCube(BMI, Set.of(C1)).isEmpty());
        assertTrue(store.getCube(BMI, Set.of(C2)).isEmpty());
        assertTrue(store.getCube(AGE, Set.of(C2)).isEmpty());
        assertTrue(store.getCube(AGE, Set.of("tutorial")).isEmpty(), "no partition was cut at the first hyphen");
        assertTrue(store.getCube(AGE, Set.of("unknown-consent")).isEmpty());
        assertThrows(MissingConsentsException.class, () -> store.getCube(AGE, Set.of()));
    }

    @Test
    void cubesOfSeveralConsentsAreMerged() throws IOException {
        PartitionedPhenotypicObservationStore store = load();

        PhenoCube<?> age = store.getCube(AGE, Set.of(C1, CAMP)).orElseThrow();
        assertEquals(Map.of(1, 30.0, 2, 40.0, 3, 50.0, 11, 60.0, 12, 70.0), valuesByPatient(age));
        PhenoCube<?> sex = store.getCube(SEX, Set.of(C1, C2)).orElseThrow();
        assertEquals(Map.of(1, "male", 2, "female", 3, "male", 21, "female", 22, "male"), valuesByPatient(sex));
    }

    @Test
    void rangeAndValueQueriesRunAgainstTheStoredCubes() throws IOException {
        PartitionedPhenotypicObservationStore store = load();

        assertEquals(Set.of(2, 3, 11), store.getKeysForRange(AGE, 35.0, 65.0, Set.of(C1, CAMP)));
        assertEquals(Set.of(), store.getKeysForRange(AGE, 100.0, 200.0, Set.of(C1, CAMP)), "a minimum above every value");
        assertEquals(Set.of(1, 3, 22), store.getKeysForValues(SEX, List.of("male"), Set.of(C1, C2)));
        assertEquals(Set.of(1, 2, 3), store.getAllKeys(SEX, Set.of(C1)));
    }

    @Test
    void theMergedMetadataKnowsEveryConceptAcrossPartitions() throws IOException {
        PartitionedPhenotypicObservationStore store = load();

        assertEquals(Set.of(AGE, SEX, BMI), store.getMetaStore().keySet());
        assertEquals(5, store.getMetaStore().get(AGE).getPatientCount());
        assertEquals(5, store.getMetaStore().get(SEX).getPatientCount());
        assertEquals(30.0, store.getMetaStore().get(AGE).getMin());
        assertEquals(70.0, store.getMetaStore().get(AGE).getMax());
        assertEquals(Set.of("male", "female"), store.getMetaStore().get(SEX).getCategoryValues());
        assertTrue(store.getMetaStore().get(SEX).hasTimestamp(), "timestamp metadata comes from ColumnMetaBuilder");
        assertEquals(1700000000000L, store.getMetaStore().get(SEX).getTimestampMin());
        assertEquals(java.time.Instant.parse("2021-01-15T10:30:00Z").toEpochMilli(), store.getMetaStore().get(BMI).getTimestampMax());
    }
}
