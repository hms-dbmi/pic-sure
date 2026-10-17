package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.PICSURELoader.RefusedException;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.StoreFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The contract the shared runner (pipelines/hpds-pheno-load) relies on. Test accessions only: phs000000, phs999999, phs123456. */
class PICSURELoaderTest {

    private static final String KEY = "1111111111111111";
    private static final String AGE = "\\phs000000\\Demographics\\Age\\";
    private static final String SEX = "\\phs000000\\Demographics\\Sex\\";
    private static final String BMI = "\\phs999999\\Measures\\BMI\\";

    private static final List<String> STORE_FILES = List.of("allObservationsStore.javabin", "columnMeta.javabin", "columnMeta.csv");

    @TempDir
    Path tempDir;

    private int run(Path base, Map<String, String> properties) {
        return PICSURELoader.run(new String[] {base.toString()}, properties::get);
    }

    private Path newBase(String name) throws IOException {
        Path base = tempDir.resolve(name);
        Files.createDirectories(base);
        Files.writeString(base.resolve("encryption_key"), KEY);
        return base;
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    private static void assertHasStore(Path partitionDir) {
        for (String file : STORE_FILES) {
            assertTrue(Files.isRegularFile(partitionDir.resolve(file)), file + " in " + partitionDir);
        }
    }

    private static void assertNoStore(Path partitionDir) {
        for (String file : STORE_FILES) {
            assertFalse(Files.exists(partitionDir.resolve(file)), file + " must not exist in " + partitionDir);
        }
    }

    private static JsonNode summary(Path base) throws IOException {
        return new ObjectMapper().readTree(Files.readString(base.resolve("ingest-summary.json")));
    }

    private static JsonNode partitionReport(JsonNode summary, String name) {
        for (JsonNode partition : summary.get("partitions")) {
            if (partition.get("name").asText().equals(name)) {
                return partition;
            }
        }
        throw new AssertionError("no partition " + name + " in " + summary);
    }

    // ---- naming ---------------------------------------------------------------------------------------------------------

    @Test
    void partitionIsTheFileNameWithoutTheSuffix() throws Exception {
        assertEquals("tutorial-biolincc_camp", PICSURELoader.partitionNameFor("tutorial-biolincc_camp-allConcepts.csv"));
        assertEquals("open_access-1000Genomes", PICSURELoader.partitionNameFor("open_access-1000Genomes-allConcepts.csv"));
        assertEquals("phs000000.c1", PICSURELoader.partitionNameFor("phs000000.c1-allConcepts.csv"));
        assertEquals("phs123456", PICSURELoader.partitionNameFor("phs123456-allConcepts.csv"));
        assertEquals("a".repeat(128), PICSURELoader.partitionNameFor("a".repeat(128) + "-allConcepts.csv"));
    }

    @Test
    void namesThatBreakTheContractAreRefused() {
        for (
            String fileName : List.of(
                "allConcepts.csv", "phs000000_allConcepts.csv", "phs000000-allconcepts.csv", "phs000000-allConcepts.csv.bak",
                "-allConcepts.csv", ".hidden-allConcepts.csv", "-lead-allConcepts.csv", "has space-allConcepts.csv",
                "semi;colon-allConcepts.csv", "a..b-allConcepts.csv", "..-allConcepts.csv", ".-allConcepts.csv", "é-allConcepts.csv",
                "a".repeat(129) + "-allConcepts.csv"
            )
        ) {
            assertThrows(RefusedException.class, () -> PICSURELoader.partitionNameFor(fileName), fileName);
        }
    }

    @Test
    void hyphenatedAndPlainPartitionsGetTheirOwnDirectoryNextToTheirFile() throws Exception {
        Path base = newBase("naming");
        write(
            base.resolve("tutorial-biolincc_camp-allConcepts.csv"), "1,\\phs000000\\Demographics\\Age\\,40,,",
            "2,\\phs000000\\Demographics\\Age\\,50,,"
        );
        write(
            base.resolve("phs000000.c1-allConcepts.csv"), "3,\\phs000000\\Demographics\\Age\\,60,,",
            "3,\\phs000000\\Demographics\\Sex\\,,female,"
        );
        Files.createDirectories(base.resolve("nested"));
        write(base.resolve("nested/phs999999.c2-allConcepts.csv"), "4,\\phs999999\\Measures\\BMI\\,22.5,,");

        assertEquals(0, run(base, Map.of()));

        assertHasStore(base.resolve("tutorial-biolincc_camp"));
        assertHasStore(base.resolve("phs000000.c1"));
        assertHasStore(base.resolve("nested/phs999999.c2"));
        assertFalse(Files.exists(base.resolve("tutorial")), "the name must not be cut at the first hyphen");
        assertFalse(Files.exists(base.resolve("phs999999.c2")), "the partition goes next to its input file");
        assertFalse(Files.exists(base.resolve(".picsure-spool")), "the spool is removed");
        for (String partition : List.of("tutorial-biolincc_camp", "phs000000.c1", "nested/phs999999.c2")) {
            assertTrue(Files.isRegularFile(base.resolve(partition).resolve("failures.jsonl")));
            assertTrue(Files.isRegularFile(base.resolve(partition).resolve("concept_rejections.jsonl")));
        }

        JsonNode summary = summary(base);
        assertEquals("ok", summary.get("status").asText());
        assertEquals(3, summary.get("partitions").size());
        JsonNode camp = partitionReport(summary, "tutorial-biolincc_camp");
        assertEquals("ok", camp.get("status").asText());
        assertEquals(2, camp.get("rowsRead").asLong());
        assertEquals(2, camp.get("observationsWritten").asLong());
        assertEquals(1, camp.get("concepts").asInt());
        assertEquals(2, camp.get("patients").asInt());
        assertTrue(camp.get("reconciled").asBoolean());
        assertEquals(0, camp.get("rowsRejected").size());
    }

    @Test
    void inputFilesThatDoNotEndInExactlyTheSuffixAreRefusedBeforeAnyWork() throws Exception {
        Path base = newBase("refuse-suffix");
        write(base.resolve("phs000000-allConcepts.csv"), "1,\\a\\,1,,");
        write(base.resolve("legacy_allConcepts.csv"), "1,\\a\\,1,,");

        assertEquals(2, run(base, Map.of()));

        assertFalse(Files.exists(base.resolve("phs000000")), "no partition may be written when another input is refused");
        assertEquals("refused", summary(base).get("status").asText());
    }

    @Test
    void duplicatePartitionNamesAreRefusedAlsoIgnoringCase() throws Exception {
        Path base = newBase("refuse-duplicates");
        Files.createDirectories(base.resolve("one"));
        Files.createDirectories(base.resolve("two"));
        write(base.resolve("one/Phs000000-allConcepts.csv"), "1,\\a\\,1,,");
        write(base.resolve("two/phs000000-allConcepts.csv"), "1,\\a\\,1,,");

        assertEquals(2, run(base, Map.of()));

        assertFalse(Files.exists(base.resolve("one/Phs000000")));
        assertFalse(Files.exists(base.resolve("two/phs000000")));
    }

    @Test
    void noInputIsRefused() throws Exception {
        Path base = newBase("empty");

        assertEquals(2, run(base, Map.of()));
    }

    @Test
    void usageErrorsAreRefused() throws Exception {
        assertEquals(2, PICSURELoader.run(new String[] {}, key -> null));
        assertEquals(2, PICSURELoader.run(new String[] {"a", "b"}, key -> null));
        assertEquals(2, PICSURELoader.run(new String[] {tempDir.resolve("missing").toString()}, key -> null));
        Path base = newBase("bad-property");
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");
        assertEquals(2, run(base, Map.of("picsure.loader.batchSize", "many")));
        assertFalse(Files.exists(base.resolve("p")));
    }

    @Test
    void aStoreThatAlreadyExistsIsRefusedUnlessOverwriteIsSet() throws Exception {
        Path base = newBase("stale");
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");
        assertEquals(0, run(base, Map.of()));
        long size = Files.size(base.resolve("p/allObservationsStore.javabin"));

        assertEquals(2, run(base, Map.of()));
        assertEquals(size, Files.size(base.resolve("p/allObservationsStore.javabin")), "a refused run leaves the store alone");

        assertEquals(0, run(base, Map.of("picsure.loader.overwrite", "true")));
        assertHasStore(base.resolve("p"));
    }

    // ---- key ------------------------------------------------------------------------------------------------------------

    @Test
    void aMissingKeyIsFatalBeforeAnyWork() throws Exception {
        Path base = newBase("no-key");
        Files.delete(base.resolve("encryption_key"));
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");

        assertEquals(2, run(base, Map.of()));

        assertFalse(Files.exists(base.resolve("p")), "no partition directory may be created");
        assertFalse(Files.exists(base.resolve(".picsure-spool")));
        assertTrue(summary(base).get("error").asText().contains("encryption key"));
    }

    @Test
    void aKeyOfTheWrongLengthIsFatalAndNeverPrinted() throws Exception {
        Path base = newBase("short-key");
        Files.writeString(base.resolve("encryption_key"), "tooShortSecret\n");
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");

        assertEquals(2, run(base, Map.of()));

        assertFalse(Files.exists(base.resolve("p")));
        assertFalse(Files.readString(base.resolve("ingest-summary.json")).contains("tooShortSecret"));
    }

    @Test
    void anUnreadableKeyIsFatal() throws Exception {
        Path base = newBase("unreadable-key");
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");
        Path key = base.resolve("encryption_key");
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(key), "running as a user that can read anything");
            assertEquals(2, run(base, Map.of()));
            assertFalse(Files.exists(base.resolve("p")));
        } finally {
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void keysOf16And24And32BytesAreAccepted() throws Exception {
        for (String key : List.of("1111111111111111", "111111111111111111111111", "11111111111111111111111111111111\n")) {
            Path base = newBase("key-" + key.trim().length());
            Files.writeString(base.resolve("encryption_key"), key);
            write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");
            assertEquals(0, run(base, Map.of()), key.trim().length() + " bytes");
        }
    }

    // ---- failures -------------------------------------------------------------------------------------------------------

    @Test
    void aFailedPartitionLeavesNoStoreAndTheRunExitsNonZero() throws Exception {
        Path base = newBase("failed");
        write(base.resolve("a-allConcepts.csv"), "1,\\a\\,1,,", "2,\\a\\,2,,");
        // three observations for one concept against a limit of two: the third is refused, which fails the partition
        write(base.resolve("b-allConcepts.csv"), "1,\\b\\,1,,", "2,\\b\\,2,,", "3,\\b\\,3,,");
        write(base.resolve("c-allConcepts.csv"), "1,\\c\\,1,,");

        int rc = run(base, Map.of("picsure.loader.maxObservationsPerConcept", "2"));

        assertEquals(1, rc);
        assertHasStore(base.resolve("a"));
        assertNoStore(base.resolve("b"));
        assertNoStore(base.resolve("c"));
        assertFalse(Files.exists(base.resolve(".picsure-spool")), "the spool of the failed partition is removed too");
        JsonNode summary = summary(base);
        assertEquals("failed", summary.get("status").asText());
        assertEquals("ok", partitionReport(summary, "a").get("status").asText());
        JsonNode b = partitionReport(summary, "b");
        assertEquals("failed", b.get("status").asText());
        assertTrue(b.get("error").asText().contains("observation limit"), b.get("error").asText());
        assertEquals(1, b.get("rejectedConceptCount").asInt());
        assertEquals("not_run", partitionReport(summary, "c").get("status").asText(), "fail fast by default");
        // the reports of the failed partition stay for diagnosis
        List<String> rejections = Files.readAllLines(base.resolve("b/concept_rejections.jsonl"));
        assertEquals(1, rejections.size());
        assertTrue(rejections.get(0).contains("exceeded_max_observations_per_concept"));
    }

    @Test
    void withFailFastOffTheOtherPartitionsAreStillLoaded() throws Exception {
        Path base = newBase("failed-continue");
        write(base.resolve("a-allConcepts.csv"), "1,\\a\\,1,,");
        write(base.resolve("b-allConcepts.csv"), "1,\\b\\,1,,", "2,\\b\\,2,,", "3,\\b\\,3,,");
        write(base.resolve("c-allConcepts.csv"), "1,\\c\\,1,,");

        int rc = run(base, Map.of("picsure.loader.maxObservationsPerConcept", "2", "picsure.loader.failFast", "false"));

        assertEquals(1, rc, "still non-zero");
        assertHasStore(base.resolve("a"));
        assertNoStore(base.resolve("b"));
        assertHasStore(base.resolve("c"));
    }

    @Test
    void aPartitionWithoutAnyStorableRowFailsInsteadOfWritingAnEmptyStore() throws Exception {
        Path base = newBase("nothing");
        write(base.resolve("a-allConcepts.csv"), "not-a-patient,\\a\\,1,,", "2,\\a\\,,,");
        write(base.resolve("b-allConcepts.csv"), "1,\\b\\,,NA,", "2,\\b\\,,None,");

        assertEquals(1, run(base, Map.of("picsure.loader.failFast", "false")));

        assertNoStore(base.resolve("a"));
        assertNoStore(base.resolve("b"));
        JsonNode summary = summary(base);
        JsonNode a = partitionReport(summary, "a");
        assertEquals(2, a.get("rowsRead").asLong());
        assertEquals(1, a.get("rowsRejected").get("INVALID_PATIENT_ID").asLong());
        assertEquals(1, a.get("rowsRejected").get("MISSING_VALUE").asLong());
        JsonNode b = partitionReport(summary, "b");
        assertEquals(2, b.get("writer").get("skippedNullSentinel").asLong(), "sentinels never create a concept");
        assertEquals(0, b.get("concepts").asInt());
    }

    @Test
    void aStoreFromAnEarlierRunDoesNotSurviveAFailedOverwrite() throws Exception {
        Path base = newBase("failed-overwrite");
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");
        assertEquals(0, run(base, Map.of()));
        assertHasStore(base.resolve("p"));
        write(base.resolve("p-allConcepts.csv"), "x,\\a\\,1,,");

        assertEquals(1, run(base, Map.of("picsure.loader.overwrite", "true")));

        assertNoStore(base.resolve("p"));
    }

    @Test
    void aDirectoryNamedLikeAnInputFileIsIgnored() throws Exception {
        // not a regular file: ignored, not a partition and not a crash
        Path base = newBase("directory-named-like-input");
        Files.createDirectories(base.resolve("odd-allConcepts.csv"));
        write(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,");

        assertEquals(0, run(base, Map.of()));
        assertHasStore(base.resolve("p"));
    }

    // ---- symbolic links (the shared runner links its read-only input into the base directory) -----------------------------

    @Test
    void symlinkedInputAndKeyAreReadAndNothingIsWrittenNextToTheLinkTargets() throws Exception {
        Path targets = tempDir.resolve("read-only-inputs");
        Files.createDirectories(targets);
        Path keyTarget = targets.resolve("hpds_key");
        Files.writeString(keyTarget, KEY);
        Path campTarget = targets.resolve("tutorial-biolincc_camp-allConcepts.csv");
        write(
            campTarget, "2,\\phs000000\\Demographics\\Age\\,50,,", "1,\\phs000000\\Demographics\\Age\\,40,,",
            "1,\\phs000000\\Demographics\\Sex\\,,male,"
        );
        Path plainTarget = targets.resolve("phs999999.c2-allConcepts.csv");
        write(plainTarget, "3,\\phs999999\\Measures\\BMI\\,22.5,,");
        Map<String, Long> before = snapshot(targets);

        Path base = tempDir.resolve("runner-base");
        Files.createDirectories(base);
        Files.createSymbolicLink(base.resolve("encryption_key"), keyTarget);
        Files.createSymbolicLink(base.resolve("tutorial-biolincc_camp-allConcepts.csv"), campTarget);
        Files.createSymbolicLink(base.resolve("phs999999.c2-allConcepts.csv"), plainTarget);

        // read-only targets, the way the runner mounts its input
        for (Path p : List.of(keyTarget, campTarget, plainTarget)) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("r--------"));
        }
        Files.setPosixFilePermissions(targets, PosixFilePermissions.fromString("r-x------"));
        try {
            assertEquals(0, run(base, Map.of()));
        } finally {
            Files.setPosixFilePermissions(targets, PosixFilePermissions.fromString("rwx------"));
            for (Path p : List.of(keyTarget, campTarget, plainTarget)) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            }
        }

        assertHasStore(base.resolve("tutorial-biolincc_camp"));
        assertHasStore(base.resolve("phs999999.c2"));
        assertEquals(before, snapshot(targets), "nothing may be written next to, or into, the link targets");
        try (Stream<Path> listing = Files.list(targets)) {
            assertEquals(3, listing.count());
        }
        for (String link : List.of("encryption_key", "tutorial-biolincc_camp-allConcepts.csv", "phs999999.c2-allConcepts.csv")) {
            assertTrue(Files.isSymbolicLink(base.resolve(link)), "the links stay links");
        }
        StoreFixture.loadDefaultKey(tempDir);
        TreeMap<String, ColumnMeta> metas = StoreFixture.readColumnMeta(base.resolve("tutorial-biolincc_camp"));
        assertEquals(Set.of("\\phs000000\\Demographics\\Age\\", "\\phs000000\\Demographics\\Sex\\"), metas.keySet());
    }

    private static Map<String, Long> snapshot(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).collect(Collectors.toMap(p -> dir.relativize(p).toString(), p -> {
                try {
                    return Files.size(p) * 31 + Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
    }

    // ---- what the runner's DecryptCheck verifies ---------------------------------------------------------------------------

    @Test
    void columnMetaCsvIsInTheLegacyElevenColumnFormat() throws Exception {
        Path base = newBase("csv");
        write(
            base.resolve("p-allConcepts.csv"), "1,\\phs000000\\Demographics\\Age\\,40,,", "2,\\phs000000\\Demographics\\Age\\,55,,",
            "1,\\phs000000\\Demographics\\Sex\\,,male,", "2,\\phs000000\\Demographics\\Sex\\,,female,"
        );

        assertEquals(0, run(base, Map.of()));

        List<org.apache.commons.csv.CSVRecord> rows;
        try (var parser = org.apache.commons.csv.CSVFormat.DEFAULT.parse(Files.newBufferedReader(base.resolve("p/columnMeta.csv")))) {
            rows = parser.getRecords();
        }
        assertEquals(2, rows.size());
        var age = rows.get(0);
        assertEquals(11, age.size());
        assertEquals(AGE, age.get(0));
        assertEquals("8", age.get(1));
        assertEquals("false", age.get(3));
        assertEquals("", age.get(4));
        assertEquals("40.0", age.get(5));
        assertEquals("55.0", age.get(6));
        assertEquals("2", age.get(9));
        assertEquals("2", age.get(10));
        var sex = rows.get(1);
        assertEquals(SEX, sex.get(0));
        assertEquals("true", sex.get(3));
        assertEquals("femaleµmale", sex.get(4));
        assertEquals("null", sex.get(5), "a missing min is the text null, as the legacy loader writes it");
        assertEquals("null", sex.get(6));
        assertEquals("2", sex.get(9));

        StoreFixture.loadDefaultKey(tempDir);
        PhenoCube<?> cube = StoreFixture.readCube(base.resolve("p"), AGE);
        assertEquals(AGE, cube.name);
        assertEquals(Crypto.DEFAULT_KEY_NAME, "DEFAULT");
    }

    @Test
    void ingestSummaryReportsRejectionsWarningsAndTimings() throws Exception {
        Path base = newBase("summary");
        write(
            base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,", "2,\\a\\,2,,not-a-date", "3,\\a\\,3,ignored,", "x,\\a\\,4,,", "4,\\a\\,,,",
            "5,\\a\\,abc,text,", "6,\\b\\,,NA,", "7,\\b\\,,word,"
        );

        assertEquals(0, run(base, Map.of("picsure.loader.runId", "run-42")));

        JsonNode summary = summary(base);
        assertEquals("run-42", summary.get("runId").asText());
        JsonNode p = partitionReport(summary, "p");
        assertEquals(8, p.get("rowsRead").asLong());
        assertEquals(1, p.get("rowsRejected").get("INVALID_PATIENT_ID").asLong());
        assertEquals(1, p.get("rowsRejected").get("MISSING_VALUE").asLong());
        assertEquals(1, p.get("rowsKeptWithWarning").get("TIMESTAMP_PARSE_ERROR").asLong());
        assertEquals(1, p.get("rowsKeptWithWarning").get("BOTH_VALUES_TEXT_DISCARDED").asLong());
        assertEquals(1, p.get("rowsKeptWithWarning").get("NUMERIC_PARSE_ERROR").asLong());
        assertEquals(6, p.get("observationsProduced").asLong());
        // \a\: 1,2,3 numeric and "text" (promotes the concept to categorical); \b\: the NA is kept as a category of a categorical concept
        assertEquals(2, p.get("concepts").asInt());
        assertTrue(p.get("reconciled").asBoolean(), p.toString());
        assertTrue(p.get("timingsMs").get("ingestMs").asLong() >= 0);
        assertTrue(p.get("timingsMs").get("finalizeMs").asLong() >= 0);
        assertTrue(p.get("timingsMs").get("totalMs").asLong() >= 0);
        assertEquals(1, summary.get("totals").get("partitionsOk").asInt());
    }
}
