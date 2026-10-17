package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.HPDSWriterAdapter;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.StoreFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The producer feeding the spooling writer, as the PICSURELoader driver will wire them: an unsorted allConcepts file in, a javabin store
 * out. Each parallel-path variant must give the same store as the sequential one.
 */
class CsvToStoreTest {

    private static final String AGE = "\\phs000000\\Demographics\\Age\\";
    private static final String SEX = "\\phs000000\\Demographics\\Sex\\";
    private static final String BMI = "\\phs000000\\Measures\\BMI\\";

    @TempDir
    Path tempDir;

    /** Concepts interleaved on purpose, both-values rows, epoch and ISO timestamps, a duplicate row, a bad row. */
    private static final String CSV = String.join(
        "\n", "1,\\phs000000\\Demographics\\Age\\,40,,1700000000000", "1,\\phs000000\\Demographics\\Sex\\,,female,0",
        "2,\\phs000000\\Measures\\BMI\\,22.5,,2021-01-15T10:30:00Z", "2,\\phs000000\\Demographics\\Age\\,55,old,",
        "3,\\phs000000\\Demographics\\Sex\\,,male,2023-11-25 14:00:00", "3,\\phs000000\\Measures\\BMI\\,31.0,,",
        "1,\\phs000000\\Demographics\\Age\\,40,,1700000000000", "not-an-id,\\phs000000\\Measures\\BMI\\,1,,", ""
    );

    private TreeMap<String, ColumnMeta> load(CsvObservationProducer producer, Path outputDir) throws Exception {
        Path key = tempDir.resolve("encryption_key");
        Files.writeString(key, "1111111111111111");
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, key.toString());
        Path input = tempDir.resolve("study-allConcepts.csv");
        Files.writeString(input, CSV);

        try (
            HPDSWriterAdapter adapter = new HPDSWriterAdapter(
                tempDir.resolve("spool-" + outputDir.getFileName()), outputDir, Crypto.DEFAULT_KEY_NAME, 1, 1000, 2, 10
            )
        ) {
            producer.processFile(input, adapter::acceptBatch, 3);
        }
        return StoreFixture.readColumnMeta(outputDir);
    }

    private void assertExpectedStore(Path outputDir, TreeMap<String, ColumnMeta> metas) throws Exception {
        assertEquals(Set.of(AGE, SEX, BMI), metas.keySet());

        // the duplicate Age row for patient 1 is collapsed; patient 2 has both values and keeps the number
        assertEquals(2, metas.get(AGE).getObservationCount());
        assertFalse(metas.get(AGE).isCategorical());
        PhenoCube<?> age = StoreFixture.readCube(outputDir, AGE);
        assertEquals(Map.of(1, 40.0, 2, 55.0), valueByPatient(age));
        assertEquals(1700000000000L, age.sortedByKey()[0].getTimestamp());
        assertNull(age.sortedByKey()[1].getTimestamp());
        assertEquals(1700000000000L, metas.get(AGE).getTimestampMin());

        assertTrue(metas.get(SEX).isCategorical());
        assertEquals(Map.of(1, "female", 3, "male"), valueByPatient(StoreFixture.readCube(outputDir, SEX)));
        assertEquals(java.time.Instant.parse("2023-11-25T14:00:00Z").toEpochMilli(), metas.get(SEX).getTimestampMin());

        assertEquals(22.5, metas.get(BMI).getMin());
        assertEquals(31.0, metas.get(BMI).getMax());
        assertEquals(Set.of(1, 2, 3), StoreFixture.readAllIds(outputDir));
    }

    private static Map<Integer, Object> valueByPatient(PhenoCube<?> cube) {
        Map<Integer, Object> values = new TreeMap<>();
        for (KeyAndValue<?> kv : cube.sortedByKey()) {
            values.put(kv.getKey(), kv.getValue());
        }
        return values;
    }

    @Test
    void sequentialPath() throws Exception {
        Path out = tempDir.resolve("sequential");
        TreeMap<String, ColumnMeta> metas = load(new CsvObservationProducer("run", mock(FailureSink.class)), out);

        assertExpectedStore(out, metas);
    }

    @Test
    void parallelPath() throws Exception {
        Path out = tempDir.resolve("parallel");
        TreeMap<String, ColumnMeta> metas = load(new CsvObservationProducer("run", mock(FailureSink.class), 1, 2), out);

        assertExpectedStore(out, metas);
    }

    @Test
    void failuresReportListsTheRejectedAndTheAdjustedRows() throws Exception {
        Path failures = tempDir.resolve("failures.jsonl");
        try (FailureSink sink = new FailureSink(failures)) {
            load(new CsvObservationProducer("run", sink), tempDir.resolve("report"));
        }

        String report = Files.readString(failures);
        assertEquals(2, report.lines().count(), report);
        assertTrue(report.contains("INVALID_PATIENT_ID"));
        assertTrue(report.contains("BOTH_VALUES_TEXT_DISCARDED"));
    }
}
