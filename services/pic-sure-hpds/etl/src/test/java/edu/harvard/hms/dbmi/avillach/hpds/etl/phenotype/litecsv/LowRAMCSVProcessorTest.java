package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.litecsv;


import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

class LowRAMCSVProcessorTest {

    private static final String TEST_KEY_PATH = "src/test/resources/test_named_encryption_key";


    @Test
    void shouldProcessSimpleCSV(@TempDir File testDir) throws IOException, ClassNotFoundException {
        String content = """
            PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME
            1,\\foo\\1\\,,val,1
            1,\\foo\\2\\,0,,1
            2,\\foo\\1\\,,lav,1
            2,\\foo\\2\\,99,,1
            """;
        Path csvPath = Path.of(testDir.getAbsolutePath(), "test.csv");
        Files.writeString(csvPath, content);
        LowRAMLoadingStore store = new LowRAMLoadingStore(
            testDir.getAbsolutePath() + "/allObservationsTemp.javabin", testDir.getAbsolutePath() + "/columnMeta.javabin",
            testDir.getAbsolutePath() + "/allObservationsStore.javabin", "TEST_KEY"
        );

        IngestStatus status = new LowRAMCSVProcessor(store, false, 1D).process(csvPath.toFile());

        Assertions.assertEquals(2, status.conceptCount());
        Assertions.assertEquals(5, status.lineCount());
        Assertions.assertEquals(csvPath, status.file());

        Crypto.loadKey("TEST_KEY", TEST_KEY_PATH);
        store.saveStore();
        List<Meta> expectedMetas =
            List.of(new CategoricalMeta("\\foo\\1\\", 2, 2, List.of("lav", "val")), new NumericMeta("\\foo\\2\\", 2, 2, 0.0, 99.0));
        verifyStoreMeta(expectedMetas, testDir.getAbsolutePath() + "/columnMeta.javabin");
    }

    @Test
    void shouldNotProcessCharAndInt(@TempDir File testDir) throws IOException, ClassNotFoundException {
        String content = """
            PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME
            1,\\foo\\1\\,,val,1
            1,\\foo\\1\\,0,,1
            """;
        Path csvPath = Path.of(testDir.getAbsolutePath(), "test.csv");
        Files.writeString(csvPath, content);
        LowRAMLoadingStore store = new LowRAMLoadingStore(
            testDir.getAbsolutePath() + "/allObservationsTemp.javabin", testDir.getAbsolutePath() + "/columnMeta.javabin",
            testDir.getAbsolutePath() + "/allObservationsStore.javabin", "TEST_KEY"
        );

        IngestStatus status = new LowRAMCSVProcessor(store, false, 1D).process(csvPath.toFile());

        Assertions.assertEquals(1, status.conceptCount());
        Assertions.assertEquals(3, status.lineCount());
        Assertions.assertEquals(csvPath, status.file());

        Crypto.loadKey("TEST_KEY", TEST_KEY_PATH);
        store.saveStore();
        List<Meta> expectedMetas = List.of(
            new CategoricalMeta("\\foo\\1\\", 1, 1, List.of("val")) // make sure "1" doesn't show up
        );
        verifyStoreMeta(expectedMetas, testDir.getAbsolutePath() + "/columnMeta.javabin");
    }

    @Test
    void shouldNotProcessIntAndChar(@TempDir File testDir) throws IOException, ClassNotFoundException {
        String content = """
            PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME
            1,\\foo\\1\\,0,,1
            2,\\foo\\1\\,1,,1
            1,\\foo\\1\\,,val,1
            """;
        Path csvPath = Path.of(testDir.getAbsolutePath(), "test.csv");
        Files.writeString(csvPath, content);
        LowRAMLoadingStore store = new LowRAMLoadingStore(
            testDir.getAbsolutePath() + "/allObservationsTemp.javabin", testDir.getAbsolutePath() + "/columnMeta.javabin",
            testDir.getAbsolutePath() + "/allObservationsStore.javabin", "TEST_KEY"
        );

        IngestStatus status = new LowRAMCSVProcessor(store, false, 1D).process(csvPath.toFile());

        Assertions.assertEquals(1, status.conceptCount());
        Assertions.assertEquals(4, status.lineCount());
        Assertions.assertEquals(csvPath, status.file());

        Crypto.loadKey("TEST_KEY", TEST_KEY_PATH);
        store.saveStore();
        List<Meta> expectedMetas = List.of(
            new NumericMeta("\\foo\\1\\", 2, 2, 0.0, 1.0) // make sure "1" doesn't show up
        );
        verifyStoreMeta(expectedMetas, testDir.getAbsolutePath() + "/columnMeta.javabin");
    }

    @Test
    void shouldProcessLargeFile(@TempDir File testDir) throws IOException, ClassNotFoundException {
        Path csvPath = Path.of(testDir.getAbsolutePath(), "test.csv");

        // Create a ~200M file sorted by patient
        String header = "PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n";
        Files.writeString(csvPath, header);
        // 5 char patient col + 22 char concept col + 1 char numeric value + 10 char date + 4 commas + newline =
        // 43 chars of ascii-utf8 = 43 bytes per line, except the debugger says 44 so uh 44 it is
        // I want a file that splits into 2 chunks. The chunk size is set to 0.1 G, so 2 * (0.1 * 1024^3)/44
        // So we need 4880644 lines of this to make a 0.2G file that splits into 2 chunks
        // ... give or take. I shot a little under
        try (FileWriter fw = new FileWriter(csvPath.toString(), true); BufferedWriter writer = new BufferedWriter(fw)) {
            for (int line = 0; line < 4880000; line++) {
                int patient = line / 100;
                int concept = line % 100;
                int val = line % 9;
                String date = "1739329199";
                String row = String.format("%05d", patient) + ",\\my\\concept\\path\\" + String.format("%05d", concept) + "\\," + val + ",,"
                    + date + '\n';
                writer.write(row);
                if (line % 1000000 == 0) {
                    System.out.println("Wrote line: " + line);
                }
            }
        }

        LowRAMLoadingStore store = new LowRAMLoadingStore(
            testDir.getAbsolutePath() + "/allObservationsTemp.javabin", testDir.getAbsolutePath() + "/columnMeta.javabin",
            testDir.getAbsolutePath() + "/allObservationsStore.javabin", "TEST_KEY"
        );

        IngestStatus status = new LowRAMCSVProcessor(store, false, 0.1).process(csvPath.toFile());
        Assertions.assertEquals(4880001, status.lineCount());
        Assertions.assertEquals(100, status.conceptCount());
        Assertions.assertEquals(csvPath, status.file());

        Crypto.loadKey("TEST_KEY", TEST_KEY_PATH);
        store.saveStore();
        List<Meta> expectedMetas = List.of(
            new NumericMeta("\\my\\concept\\path\\00000\\", 24399, 24399, 0.0, 8.0),
            new NumericMeta("\\my\\concept\\path\\00099\\", 24400, 24400, 0.0, 8.0)
        );
        verifyStoreMeta(expectedMetas, testDir.getAbsolutePath() + "/columnMeta.javabin");
    }

    private void verifyStoreMeta(List<Meta> expectedMetas, String columnMetaPath) throws IOException, ClassNotFoundException {
        ObjectInputStream objectInputStream = new ObjectInputStream(new GZIPInputStream(new FileInputStream(columnMetaPath)));
        TreeMap<String, ColumnMeta> metaStore = (TreeMap<String, ColumnMeta>) objectInputStream.readObject();
        for (Meta expectedMeta : expectedMetas) {
            ColumnMeta actualMeta = metaStore.get(expectedMeta.key());
            Assertions.assertNotNull(actualMeta);
            Assertions.assertEquals(expectedMeta.patientCount(), actualMeta.getPatientCount());
            Assertions.assertEquals(expectedMeta.conceptCount(), actualMeta.getObservationCount());
            if (expectedMeta instanceof NumericMeta expectedNumeric) {
                Assertions.assertEquals(expectedNumeric.min(), actualMeta.getMin());
                Assertions.assertEquals(expectedNumeric.max(), actualMeta.getMax());
            } else if (expectedMeta instanceof CategoricalMeta expectedCategorical) {
                Assertions.assertEquals(expectedCategorical.values(), actualMeta.getCategoryValues());
            }
        }
    }

    private sealed interface Meta permits NumericMeta, CategoricalMeta {
        String key();

        int patientCount();

        int conceptCount();
    }
    private record NumericMeta(String key, int patientCount, int conceptCount, Double min, Double max) implements Meta {
    }
    private record CategoricalMeta(String key, int patientCount, int conceptCount, List<String> values) implements Meta {
    }
}
