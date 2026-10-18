package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.StoreFixture;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PICSURELoader on a combined allConcepts file (the input of hpds-ingest): the rows are routed to one partition per consent with the rules
 * of the split-allconcepts-by-consent job. The vectors in {@code consent-vectors/v1} were checked against that job (see its README); test
 * accessions only: phs000000, phs999999, phs123456.
 */
class CombinedInputTest {

    private static final String KEY = "1111111111111111";
    private static final String VECTORS = "/consent-vectors/v1/";
    private static final String INPUT = "combined-allConcepts-input.csv";
    private static final String EXPECTED = "expected-partitions.csv";
    private static final String COUNTS = "expected-counts.json";

    private static final String AGE = "\\phs000000\\Demographics\\Age\\";
    private static final String BMI = "\\phs999999\\Measures\\BMI\\";

    private static final List<String> PARTITIONS = List.of("phs000000.c1", "phs000000.c2", "phs123456.c1", "tutorial-biolincc_test");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static String resource(String name) throws IOException {
        try (InputStream in = CombinedInputTest.class.getResourceAsStream(VECTORS + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path newBase(String name) throws IOException {
        Path base = tempDir.resolve(name);
        Files.createDirectories(base);
        Files.writeString(base.resolve("encryption_key"), KEY);
        return base;
    }

    /** The vector as a combined file with a name that is nothing like a partition file, outside the base. */
    private Path vectorInput() throws IOException {
        Path input = tempDir.resolve("data").resolve("allConcepts_data_analyzer.csv");
        Files.createDirectories(input.getParent());
        if (!Files.exists(input)) {
            Files.writeString(input, resource(INPUT), StandardCharsets.UTF_8);
        }
        return input;
    }

    /** The vector holds a header row, a mid-file header and an unparsable patient id (2 rows CSVLoader stops on) and a bad number. */
    private static Map<String, String> props(Path input, String... more) {
        Map<String, String> p = new TreeMap<>();
        p.put("picsure.loader.input", input.toString());
        p.put("picsure.loader.maxRejectedRows", "3");
        for (int i = 0; i < more.length; i += 2) {
            p.put(more[i], more[i + 1]);
        }
        return p;
    }

    private static int run(Path base, Map<String, String> properties) {
        return PICSURELoader.run(new String[] {base.toString()}, properties::get);
    }

    private static JsonNode summary(Path base) throws IOException {
        return JSON.readTree(Files.readString(base.resolve("ingest-summary.json")));
    }

    private static List<CSVRecord> readCsv(String text) throws IOException {
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new java.io.StringReader(text))) {
            return parser.getRecords();
        }
    }

    /** expected-partitions.csv: partition name, then the five fields of the input row. */
    private static Map<String, List<List<String>>> expectedPartitions() throws IOException {
        Map<String, List<List<String>>> expected = new TreeMap<>();
        for (CSVRecord record : readCsv(resource(EXPECTED))) {
            List<String> fields = new ArrayList<>();
            record.forEach(fields::add);
            expected.computeIfAbsent(fields.get(0), k -> new ArrayList<>()).add(fields.subList(1, fields.size()));
        }
        return expected;
    }

    private static Set<String> partitionDirs(Path base) throws IOException {
        try (Stream<Path> children = Files.list(base)) {
            return children.filter(Files::isDirectory).map(d -> d.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static void assertHasStore(Path dir) {
        for (String file : PICSURELoader.STORE_FILES) {
            assertTrue(Files.isRegularFile(dir.resolve(file)), file + " in " + dir);
        }
    }

    // ---- vectors --------------------------------------------------------------------------------------------------------

    @Test
    void vectorFilesMatchTheirChecksums() throws Exception {
        List<String> lines = resource("SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(3, lines.size());
        for (String line : lines) {
            String[] parts = line.split("  ", 2);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(parts[1]).getBytes(StandardCharsets.UTF_8));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
        assertEquals(Set.of(INPUT, EXPECTED, COUNTS), lines.stream().map(l -> l.split("  ", 2)[1]).collect(Collectors.toSet()));
    }

    @Test
    void theVectorUsesOnlyPlaceholderAccessions() throws Exception {
        String text = resource(INPUT) + resource(EXPECTED);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("phs[0-9]{6}").matcher(text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        assertEquals(Set.of("phs000000", "phs999999", "phs123456"), found);
    }

    @Test
    void theRouterWritesExactlyTheExpectedRowsToEachPartition() throws Exception {
        Path routed = tempDir.resolve("routed");
        ConsentRouter router = new ConsentRouter(vectorInput(), "test");
        router.learnConsents();
        assertEquals(PARTITIONS, router.partitionNames());
        ConsentRouter.Counts counts;
        try (FailureSink sink = new FailureSink(tempDir.resolve("routing-failures.jsonl"))) {
            counts = router.route(routed, sink);
        }

        Map<String, List<List<String>>> expected = expectedPartitions();
        assertEquals(expected.keySet(), counts.rowsPerPartition().keySet());
        for (Map.Entry<String, List<List<String>>> entry : expected.entrySet()) {
            List<List<String>> actual = new ArrayList<>();
            readCsv(Files.readString(routed.resolve(entry.getKey() + "-allConcepts.csv"))).forEach(r -> {
                List<String> fields = new ArrayList<>();
                r.forEach(fields::add);
                actual.add(fields);
            });
            List<List<String>> want = new ArrayList<>(entry.getValue());
            actual.sort(java.util.Comparator.comparing(Object::toString));
            want.sort(java.util.Comparator.comparing(Object::toString));
            assertEquals(want, actual, entry.getKey());
            assertEquals(want.size(), counts.rowsPerPartition().get(entry.getKey()));
        }
        assertTrue(counts.balanced());
    }

    @Test
    void rowsKeepTheirInputOrderInEveryPartition() throws Exception {
        Path routed = tempDir.resolve("routed");
        ConsentRouter router = new ConsentRouter(vectorInput(), "test");
        router.learnConsents();
        try (FailureSink sink = new FailureSink(tempDir.resolve("routing-failures.jsonl"))) {
            router.route(routed, sink);
        }

        // the _consents rows come first in the input, then the study rows, then the globals
        List<String> c1 = Files.readAllLines(routed.resolve("phs000000.c1-allConcepts.csv"));
        assertEquals("\"1\",\"\\_consents\\\",\"\",\"phs000000.c1\",\"0\"", c1.get(0));
        assertEquals("\"1\",\"\\phs000000\\Demographics\\Age\\\",\"40\",\"\",\"1700000000000\"", c1.get(4));
        assertTrue(c1.indexOf("\"1\",\"\\DCC Harmonized data set\\Age\\\",\"41\",\"\",\"\"") > c1.indexOf(c1.get(4)));
    }

    // ---- consent mode ---------------------------------------------------------------------------------------------------

    @Test
    void consentModeLoadsOnePartitionPerConsentNextToTheBase() throws Exception {
        Path base = newBase("consent");

        assertEquals(0, run(base, props(vectorInput())));

        assertEquals(new TreeSet<>(PARTITIONS), partitionDirs(base));
        for (String partition : PARTITIONS) {
            assertHasStore(base.resolve(partition));
        }
        for (String file : PICSURELoader.STORE_FILES) {
            assertFalse(Files.exists(base.resolve(file)), "no flat store: " + file);
        }
        assertFalse(Files.exists(base.resolve(".picsure-spool")), "routed files and spool are removed after a successful run");
        assertTrue(Files.isRegularFile(base.resolve("routing-failures.jsonl")));

        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(base.toString(), true);
        assertEquals(Set.of(1, 2), store.getPatientIds(Set.of("phs000000.c1")), "patient 1 and 2 hold phs000000.c1");
        assertEquals(Set.of(2, 7), store.getPatientIds(Set.of("phs000000.c2")));
        assertEquals(Set.of(1, 3), store.getPatientIds(Set.of("tutorial-biolincc_test")), "the hyphenated consent is its own partition");
        assertEquals(Set.of(4), store.getPatientIds(Set.of("phs123456.c1")));
        assertTrue(store.getCube(AGE, Set.of("tutorial-biolincc_test")).isEmpty(), "study rows stay in their study");
        assertTrue(store.getCube(BMI, Set.of("phs123456.c1")).isEmpty(), "patient 4 has no phs999999 row");
        assertTrue(store.getCube(BMI, Set.of("phs000000.c2")).isPresent(), "a study node without consents is a global variable");
    }

    @Test
    void consentModeEqualsLoadingTheExpectedFilesInSplitMode() throws Exception {
        Path combined = newBase("combined");
        assertEquals(0, run(combined, props(vectorInput())));

        Path split = newBase("split");
        for (Map.Entry<String, List<List<String>>> entry : expectedPartitions().entrySet()) {
            StringWriter text = new StringWriter();
            try (
                org.apache.commons.csv.CSVPrinter printer =
                    new org.apache.commons.csv.CSVPrinter(text, CSVFormat.DEFAULT.withQuoteMode(org.apache.commons.csv.QuoteMode.ALL))
            ) {
                for (List<String> row : entry.getValue()) {
                    printer.printRecord(row);
                }
            }
            Files.writeString(split.resolve(entry.getKey() + "-allConcepts.csv"), text.toString());
        }
        assertEquals(0, run(split, Map.of("picsure.loader.maxRejectedRows", "3")));

        StoreFixture.loadDefaultKey(tempDir);
        assertEquals(partitionDirs(split), partitionDirs(combined));
        for (String partition : PARTITIONS) {
            assertEquals(describe(split.resolve(partition)), describe(combined.resolve(partition)), partition);
            assertEquals(StoreFixture.readAllIds(split.resolve(partition)), StoreFixture.readAllIds(combined.resolve(partition)));
        }
    }

    /** Equivalence after decryption: concepts, their statistics and every value with its timestamp (the ciphertext differs per run). */
    private static Map<String, String> describe(Path dir) throws Exception {
        Map<String, String> concepts = new TreeMap<>();
        for (Map.Entry<String, ColumnMeta> entry : StoreFixture.readColumnMeta(dir).entrySet()) {
            ColumnMeta meta = entry.getValue();
            PhenoCube<?> cube = StoreFixture.readCube(dir, meta);
            List<String> values = new ArrayList<>();
            for (KeyAndValue<?> kv : cube.sortedByKey()) {
                values.add(kv.getKey() + "=" + kv.getValue() + "@" + kv.getTimestamp());
            }
            Collections.sort(values);
            concepts.put(
                entry.getKey(),
                meta.isCategorical() + "|" + meta.getObservationCount() + "|" + meta.getPatientCount() + "|" + meta.getCategoryValues()
                    + "|" + meta.getMin() + "|" + meta.getMax() + "|" + meta.getWidthInBytes() + "|" + values
            );
        }
        return concepts;
    }

    @Test
    void timestampsAreParsedByThePartitionLoader() throws Exception {
        Path base = newBase("timestamps");
        assertEquals(0, run(base, props(vectorInput())));
        StoreFixture.loadDefaultKey(tempDir);

        Map<Integer, Long> age = new TreeMap<>();
        for (KeyAndValue<?> kv : StoreFixture.readCube(base.resolve("phs000000.c1"), AGE).sortedByKey()) {
            age.put(kv.getKey(), kv.getTimestamp());
        }
        assertEquals(1700000000000L, age.get(1), "epoch milliseconds");
        assertEquals(java.time.Instant.parse("2023-06-06T15:20:00Z").toEpochMilli(), age.get(2), "ISO");
        Map<Integer, Long> sex = new TreeMap<>();
        for (KeyAndValue<?> kv : StoreFixture.readCube(base.resolve("phs000000.c1"), "\\phs000000\\Demographics\\Sex\\").sortedByKey()) {
            sex.put(kv.getKey(), kv.getTimestamp());
        }
        assertNull(sex.get(2), "0 means no timestamp");
    }

    @Test
    void anInvalidConsentNameIsDroppedAndReportedNotRefused() throws Exception {
        Path base = newBase("invalid-consent");

        assertEquals(0, run(base, props(vectorInput())));

        assertFalse(partitionDirs(base).stream().anyMatch(d -> d.contains("bad")), "no partition for the invalid name");
        JsonNode routing = summary(base).get("routing");
        assertEquals(1, routing.get("invalidConsentNamesDropped").size());
        JsonNode dropped = routing.get("invalidConsentNamesDropped").get(0);
        assertEquals("phs123456.bad/name", dropped.get("value").asText());
        assertEquals(7, dropped.get("rows").asLong(), "copies meant for the dropped consent");
        assertTrue(dropped.get("problem").asText().contains("does not match"));
        assertEquals(3, routing.get("invalidConsentRowsRejected").asLong(), "patient 5 has no other consent");
        // patient 4 keeps its other consent, and its row in _consents for the dropped one is a global row, kept
        StoreFixture.loadDefaultKey(tempDir);
        assertEquals(Set.of(4), StoreFixture.readAllIds(base.resolve("phs123456.c1")));
    }

    @Test
    void unroutableRowsAreCountedAndRecorded() throws Exception {
        Path base = newBase("unroutable");

        assertEquals(0, run(base, props(vectorInput())));

        JsonNode routing = summary(base).get("routing");
        assertEquals(3, routing.get("unroutableRows").asLong());
        assertEquals(2, routing.get("unroutableByReason").get("INVALID_PATIENT_ID").asLong(), "the header in the middle and 'abc'");
        assertEquals(1, routing.get("unroutableByReason").get("MALFORMED_RECORD").asLong(), "a row of 3 columns");
        List<String> reasons = new ArrayList<>();
        for (String line : Files.readAllLines(base.resolve("routing-failures.jsonl"))) {
            reasons.add(JSON.readTree(line).get("reasonCode").asText());
        }
        assertEquals(2, Collections.frequency(reasons, "INVALID_PATIENT_ID"));
        assertEquals(1, Collections.frequency(reasons, "MALFORMED_RECORD"));
        assertEquals(4, Collections.frequency(reasons, "NO_CONSENT"), "three study rows and one global row of patients without a consent");
        assertEquals(3, Collections.frequency(reasons, "INVALID_CONSENT_NAME"));
    }

    @Test
    void tooManyUnroutableRowsFailTheRunBeforeAnyPartitionIsLoaded() throws Exception {
        Path base = newBase("unroutable-limit");

        int rc = run(base, props(vectorInput(), "picsure.loader.maxRejectedRows", "1"));

        assertEquals(1, rc);
        JsonNode summary = summary(base);
        assertEquals("failed", summary.get("status").asText());
        assertTrue(summary.get("error").asText().contains("could not be routed"), summary.get("error").asText());
        assertEquals(3, summary.get("routing").get("unroutableRows").asLong(), "the counts are in the summary");
        for (String partition : PARTITIONS) {
            assertFalse(Files.exists(base.resolve(partition).resolve("allObservationsStore.javabin")));
        }
        assertFalse(Files.exists(base.resolve(".picsure-spool")), "HPDS would read it as a partition");
    }

    @Test
    void ingestSummaryDescribesTheInputAndTheRouting() throws Exception {
        Path base = newBase("summary");
        Path input = vectorInput();

        assertEquals(0, run(base, props(input)));

        JsonNode summary = summary(base);
        assertEquals("ok", summary.get("status").asText());
        assertEquals("combined", summary.get("inputMode").asText());
        assertEquals("consent", summary.get("partitionBy").asText());
        assertEquals(input.toString(), summary.get("inputFile").asText());
        assertEquals(Files.size(input), summary.get("inputSizeBytes").asLong());
        assertEquals("pic-sure-hpds-etl@6416d91", summary.get("consentRulesMatch").asText());
        assertEquals(4, summary.get("totals").get("partitionsOk").asLong());

        JsonNode expected = JSON.readTree(resource(COUNTS));
        JsonNode routing = summary.get("routing");
        for (
            String field : List.of(
                "rowsRead", "rowsRouted", "studyRows", "globalRows", "noConsentStudyRows", "noConsentGlobalRows",
                "invalidConsentRowsRejected", "unroutableRows", "linesWritten", "patientsWithConsents"
            )
        ) {
            assertEquals(expected.get(field), routing.get(field), field);
        }
        assertEquals(expected.get("rowsPerPartition"), routing.get("rowsPerPartition"));
        assertEquals(expected.get("studyNodesWithoutConsents"), routing.get("studyNodesWithoutConsents"));
        assertEquals(
            expected.get("invalidConsentNamesDropped").get(0).get("value"), routing.get("invalidConsentNamesDropped").get(0).get("value")
        );
        assertEquals(
            expected.get("invalidConsentNamesDropped").get(0).get("rows"), routing.get("invalidConsentNamesDropped").get(0).get("rows")
        );
        assertEquals(4, routing.get("noConsentRowsDropped").asLong());
        assertEquals(1, routing.get("patientsWithoutConsent").asLong(), "only patient 6 has no consent at all");
        assertTrue(routing.get("headerRowSkipped").asBoolean());
        assertTrue(routing.get("balanced").asBoolean());
        assertEquals(
            routing.get("linesWritten").asLong(), summary.get("totals").get("rowsRead").asLong(), "every routed line is read by a partition"
        );
        for (JsonNode partition : summary.get("partitions")) {
            assertEquals(routing.get("rowsPerPartition").get(partition.get("name").asText()), partition.get("rowsRead"));
            assertTrue(partition.get("reconciled").asBoolean());
        }
    }

    @Test
    void aSplitRunSaysSoInItsSummary() throws Exception {
        Path base = newBase("split-summary");
        Files.writeString(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,\n");

        assertEquals(0, PICSURELoader.run(new String[] {base.toString()}, key -> null));

        JsonNode summary = summary(base);
        assertEquals("split", summary.get("inputMode").asText());
        assertTrue(summary.get("partitionBy").isNull());
        assertTrue(summary.get("inputFile").isNull());
        assertFalse(summary.has("routing"));
        assertFalse(summary.has("consentRulesMatch"));
    }

    @Test
    void routedFilesAreKeptForAFailedPartitionAndRemovedForTheOthers() throws Exception {
        Path base = newBase("failed-partition");

        // phs000000.c1 holds 4 rows of _consents; the limit refuses the 4th, which fails that partition. phs123456.c1 holds 2 rows
        int rc = run(base, props(vectorInput(), "picsure.loader.maxObservationsPerConcept", "3", "picsure.loader.failFast", "false"));

        assertEquals(1, rc);
        Path routed = base.resolve(".picsure-spool/.routed");
        assertTrue(Files.isRegularFile(routed.resolve("phs000000.c1-allConcepts.csv")), "kept for diagnosis");
        assertFalse(Files.exists(routed.resolve("phs123456.c1-allConcepts.csv")), "deleted after its partition succeeded");
        assertFalse(Files.exists(base.resolve("phs000000.c1/allObservationsStore.javabin")));
        assertHasStore(base.resolve("phs123456.c1"));
    }

    @Test
    void partitionByDefaultsToConsentForACombinedInput() throws Exception {
        assertEquals("consent", LoaderConfig.fromProperties(Map.of("picsure.loader.input", "x.csv")::get).effectivePartitionBy());
        Path base = newBase("explicit");
        assertEquals(0, run(base, props(vectorInput(), "picsure.loader.partitionBy", "Consent")));
        assertEquals(new TreeSet<>(PARTITIONS), partitionDirs(base));
    }

    // ---- none mode ------------------------------------------------------------------------------------------------------

    @Test
    void noneModeWritesOneFlatStoreInTheBaseThatTheReaderRefusesWithAnAuthorizationFilter() throws Exception {
        Path base = newBase("none");

        assertEquals(0, run(base, props(vectorInput(), "picsure.loader.partitionBy", "none")));

        assertHasStore(base);
        assertEquals(Set.of(), partitionDirs(base), "no partition directories");
        JsonNode summary = summary(base);
        assertEquals("combined", summary.get("inputMode").asText());
        assertEquals("none", summary.get("partitionBy").asText());
        assertFalse(summary.has("routing"));
        assertEquals(1, summary.get("partitions").size());
        assertEquals("ok", summary.get("partitions").get(0).get("status").asText());

        assertThrows(IllegalStateException.class, () -> new PartitionedPhenotypicObservationStore(base.toString(), true));
        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(base.toString(), false);
        assertEquals(Set.of(1, 2, 6, 7), store.getKeysForRange(AGE, 0.0, 200.0, Set.of()), "every row is in the one store");
        assertEquals(Set.of(1, 7), store.getAllKeys(BMI, Set.of()));
    }

    // ---- refusals -------------------------------------------------------------------------------------------------------

    @Test
    void partitionByWithSplitInputIsRefused() throws Exception {
        Path base = newBase("split-partition-by");
        Files.writeString(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,\n");

        assertEquals(2, run(base, Map.of("picsure.loader.partitionBy", "consent")));
        assertEquals(2, run(base, Map.of("picsure.loader.partitionBy", "none")));

        assertFalse(Files.exists(base.resolve("p/allObservationsStore.javabin")), "nothing was loaded");
        assertTrue(summary(base).get("error").asText().contains("partitionBy"));
    }

    @Test
    void anUnknownPartitionByIsRefused() throws Exception {
        Path base = newBase("bad-partition-by");

        assertEquals(2, run(base, props(vectorInput(), "picsure.loader.partitionBy", "study")));

        assertEquals(Set.of(), partitionDirs(base));
    }

    @Test
    void aMissingInputFileIsRefused() throws Exception {
        Path base = newBase("missing-input");

        assertEquals(2, run(base, props(tempDir.resolve("nothing.csv"))));
        assertEquals(2, run(base, props(tempDir)), "a directory is not an input file");

        assertEquals("refused", summary(base).get("status").asText());
    }

    @Test
    void aBaseWithAFlatStoreIsRefusedInConsentMode() throws Exception {
        Path base = newBase("root-store");
        Files.writeString(base.resolve("allObservationsStore.javabin"), "x");

        assertEquals(2, run(base, props(vectorInput())));
        assertEquals(2, run(base, props(vectorInput(), "picsure.loader.overwrite", "true")), "overwrite does not mix layouts");

        assertEquals(Set.of(), partitionDirs(base));
        assertTrue(summary(base).get("error").asText().contains("flat store"));
    }

    @Test
    void aBaseWithPartitionStoresIsRefusedInNoneMode() throws Exception {
        Path base = newBase("partition-stores");
        Files.createDirectories(base.resolve("phs000000.c1"));
        Files.writeString(base.resolve("phs000000.c1/allObservationsStore.javabin"), "x");

        assertEquals(2, run(base, props(vectorInput(), "picsure.loader.partitionBy", "none")));
        assertEquals(
            2, run(base, props(vectorInput(), "picsure.loader.partitionBy", "none", "picsure.loader.overwrite", "true")),
            "overwrite does not mix layouts"
        );

        assertFalse(Files.exists(base.resolve("allObservationsStore.javabin")));
    }

    @Test
    void aFlatStoreAlreadyInTheBaseIsRefusedInNoneModeUnlessOverwriteIsSet() throws Exception {
        Path base = newBase("flat-again");
        Map<String, String> none = props(vectorInput(), "picsure.loader.partitionBy", "none");
        assertEquals(0, run(base, none));

        assertEquals(2, run(base, none));

        Map<String, String> overwrite = new TreeMap<>(none);
        overwrite.put("picsure.loader.overwrite", "true");
        assertEquals(0, run(base, overwrite));
    }

    @Test
    void aStoreInAPartitionDirectoryIsRefusedInConsentModeUnlessOverwriteIsSet() throws Exception {
        Path base = newBase("stale-partition");
        Files.createDirectories(base.resolve("phs000000.c1"));
        Files.writeString(base.resolve("phs000000.c1/columnMeta.csv"), "x");

        assertEquals(2, run(base, props(vectorInput())));
        assertFalse(Files.exists(base.resolve("phs000000.c2")), "nothing is written when one partition is refused");

        assertEquals(0, run(base, props(vectorInput(), "picsure.loader.overwrite", "true")));
        assertHasStore(base.resolve("phs000000.c1"));
    }

    @Test
    void aCombinedFileWithoutConsentsIsRefused() throws Exception {
        Path base = newBase("no-consents");
        Path input = tempDir.resolve("no-consents.csv");
        Files.writeString(input, "1,\\phs000000\\Demographics\\Age\\,40,,\n");

        assertEquals(2, run(base, props(input)));

        assertTrue(summary(base).get("error").asText().contains("no valid consent"));
    }

    @Test
    void consentsThatDifferOnlyByCaseAreRefused() throws Exception {
        Path base = newBase("case-clash");
        Path input = tempDir.resolve("clash.csv");
        Files.writeString(input, "1,\\_consents\\,,phs000000.c1,\n2,\\_consents\\,,PHS000000.C1,\n1,\\phs000000\\A\\,1,,\n");

        assertEquals(2, run(base, props(input)));

        assertTrue(summary(base).get("error").asText().contains("differ only by case"));
        assertEquals(Set.of(), partitionDirs(base));
    }

    @Test
    void theSpoolDirectoryPropertyMovesTheRoutedFiles() throws Exception {
        Path base = newBase("spool-dir");
        Path spool = tempDir.resolve("fast-disk");

        assertEquals(
            0, run(base, props(vectorInput(), "picsure.loader.spoolDir", spool.toString(), "picsure.loader.maxObservationsPerConcept", "0"))
        );

        assertFalse(Files.exists(base.resolve(".picsure-spool")));
        assertTrue(Files.isDirectory(spool), "a configured spool root is not deleted");
        try (Stream<Path> left = Files.walk(spool)) {
            assertEquals(List.of(), left.filter(Files::isRegularFile).toList(), "no routed file is left");
        }
    }
}
