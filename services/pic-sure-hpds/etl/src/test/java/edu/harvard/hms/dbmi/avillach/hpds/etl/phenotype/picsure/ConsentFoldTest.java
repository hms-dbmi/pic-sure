package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
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
 * Review round 4 of the combined input: the same study written in another spelling (U+FFFD, a blank inside, case) must never turn a data
 * row of that study into a global row. Every consents value registers its study exactly and folded; a node that is no exact study but folds
 * to a known one is unroutable (INVALID_STUDY_NODE). Vectors in {@code consent-vectors/v4}; test accessions only: phs000000, phs999999,
 * phs123456.
 */
class ConsentFoldTest {

    private static final String KEY = "1111111111111111";
    private static final String V4 = "/consent-vectors/v4/";
    private static final String INPUT = "combined-allConcepts-input.csv";
    private static final String COLLISION = "case-collision-input.csv";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FFFD = "\uFFFD";

    @TempDir
    Path tempDir;

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static String resource(String dir, String name) throws IOException {
        try (InputStream in = ConsentFoldTest.class.getResourceAsStream(dir + name)) {
            assertNotNull(in, dir + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path newBase(String name) throws IOException {
        Path base = tempDir.resolve("bases").resolve(name);
        Files.createDirectories(base);
        Files.writeString(base.resolve("encryption_key"), KEY);
        return base;
    }

    private Path writeInput(String name, String text) throws IOException {
        Path input = tempDir.resolve("data").resolve(name);
        Files.createDirectories(input.getParent());
        Files.writeString(input, text, StandardCharsets.UTF_8);
        return input;
    }

    private static Map<String, String> props(Path input, String... more) {
        Map<String, String> p = new TreeMap<>();
        p.put("picsure.loader.input", input.toString());
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

    private static Set<String> subdirectories(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).map(d -> d.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private Path lastInput;
    private Path lastRouted;

    /** Routes the lines (pass 1 and 2); the routed files are under {@link #lastRouted}, the input is {@link #lastInput}. */
    private ConsentRouter.Counts route(String... lines) throws IOException {
        lastInput = writeInput("small.csv", String.join("\n", lines) + "\n");
        lastRouted = tempDir.resolve("small-routed");
        ConsentRouter router = new ConsentRouter(lastInput, "test");
        router.learnConsents();
        try (FailureSink sink = new FailureSink(tempDir.resolve("small-failures.jsonl"))) {
            return router.route(lastRouted, sink);
        }
    }

    private static String partitionText(Path routed, String partition) throws IOException {
        Path file = routed.resolve(partition + "-allConcepts.csv");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    // ---- the reviewer's three spellings --------------------------------------------------------------------------------------

    @Test
    void aStudyWrittenWithU_FFFDInAnInvalidConsentIsNotAGlobalVariable() throws Exception {
        // canonical row, passes the row check, but PARTITION_NAME rejects the name: it registers the study 'phs999999<FFFD>' only
        ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,," + "phs999999" + FFFD + ".c1,0", "1,\\phs999999\\X\\,2,,");

        assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason());
        assertEquals(1, counts.unroutableCrashClassRows());
        assertFalse(partitionText(lastRouted, "phs000000.c1").contains("phs999999\\"), "not copied into phs000000.c1");
        assertEquals(0, counts.studyNodesWithoutConsents().size());
        assertTrue(counts.balanced());
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void aStudyWrittenWithABlankInsideIsNotAGlobalVariable() throws Exception {
        ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,phs999999 .c1,0", "1,\\phs999999\\X\\,2,,");

        assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason());
        assertFalse(partitionText(lastRouted, "phs000000.c1").contains("phs999999\\"));
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void aValidConsentInUpperCaseDoesNotMakeLowerCaseRowsGlobalAndKeepsItsOwnRows() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,PHS999999.c1,0", "1,\\phs999999\\X\\,2,,", "1,\\PHS999999\\X\\,3,,",
            "2,\\_consents\\,,phs000000.c1,0", "2,\\PHS999999\\X\\,4,,"
        );

        assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason(), "the lower case node of patient 1");
        assertEquals(1, counts.noConsentStudyRows(), "the upper case node of patient 2: a study row without a consent");
        assertTrue(partitionText(lastRouted, "PHS999999.c1").contains("\\PHS999999\\X\\"));
        assertFalse(partitionText(lastRouted, "phs000000.c1").contains("999999\\X"));
        assertTrue(counts.balanced());
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void everyFoldedSpellingOfAKnownStudyIsUnroutableAndAStudyNoConsentNamesStaysGlobal() throws Exception {
        List<String> nodes = List.of(
            "PHS999999", "Phs999999", "phs999999" + FFFD, FFFD + "phs999999", "phs 999999", "phs999 999", "phs999999 ", "phs999999​",
            "phs999999.x", " phs999999", "phs999999\t", "pHs999999"
        );
        for (String node : nodes) {
            List<String> lines = new ArrayList<>(List.of("1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,phs999999.c1,0"));
            lines.add("1,\\" + node + "\\X\\,1,,");
            ConsentRouter.Counts counts = route(lines.toArray(String[]::new));

            assertEquals(1, counts.unroutableCrashClassRows(), node.replace(FFFD, "<FFFD>"));
            assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason(), node);
            assertEquals(2, counts.globalRows(), "only the two consents rows are global: " + node);
            RoutedInvariant.assertHolds(lastInput, lastRouted);
        }
        // phs123456 is named by no consents value at all: global, reported (splitter parity, tracked separately)
        ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,0", "1,\\phs123456\\Labs\\,1,,", "1,\\Phs123456\\Labs\\,2,,");
        assertEquals(0, counts.unroutableRows());
        assertEquals(3, counts.globalRows());
        assertEquals(List.of("phs123456"), counts.studyNodesWithoutConsents());
    }

    @Test
    void aRejectedConsentsRowRegistersTheFoldedStudyToo() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,5,PHS999999.c1,0", "1,\\phs999999\\X\\,1,,", "1,\\PHS999999\\X\\,2,,"
        );

        assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason(), "the spelling of the rejected row is exact: dropped");
        assertEquals(1, counts.noConsentStudyRows());
        assertEquals(List.of("PHS999999"), counts.studiesOnlyInRejectedConsentRows());
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void aRejectedConsentsRowWithShiftedColumnsStillNamesItsStudy() throws Exception {
        for (String shifted : List.of("1,\\_consents\\,phs999999.c1,0", "1,\\_consents\\,,phs999999.c1", "1,\\_consents\\,phs999999.c1")) {
            ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,0", shifted, "1,\\phs999999\\X\\,1,,");
            assertEquals(1, counts.noConsentStudyRows(), shifted);
            assertEquals(1, counts.globalRows(), shifted);
        }
    }

    // ---- two valid spellings of one study -----------------------------------------------------------------------------------------

    @Test
    void validConsentsThatDifferOnlyInTheCaseOfTheirStudyRefuseTheRunBeforeAnyWork() throws Exception {
        Path input = writeInput(COLLISION, resource(V4, COLLISION));
        Path base = newBase("collision");

        for (Map<String, String> properties : List.of(
            props(input), props(input, "picsure.loader.maxRejectedRows", "1000", "picsure.loader.maxRejectedConsentRows", "1000")
        )) {
            assertEquals(2, run(base, properties));
            JsonNode summary = summary(base);
            assertEquals("refused", summary.get("status").asText());
            String error = summary.get("error").asText();
            assertTrue(error.contains("different spellings"), error);
            assertTrue(error.contains("PHS999999 / phs999999"), error);
            assertEquals(Set.of(), subdirectories(base));
            assertFalse(Files.exists(CombinedInputTest.spoolOf(base)), "no routed file was written");
        }
        ConsentRouter router = new ConsentRouter(input, "t");
        router.learnConsents();
        assertEquals(List.of("PHS999999 / phs999999"), router.studyFoldClashes());
    }

    @Test
    void aClashOfAnInvalidNameWithAValidOneIsNotARefusalButItsRowsAreUnroutable() throws Exception {
        Path input = writeInput("noclash.csv", "1,\\_consents\\,,phs999999.c1,0\n1,\\_consents\\,,PHS999999 .c1,0\n1,\\PHS999999\\X\\,1,,\n");
        ConsentRouter router = new ConsentRouter(input, "t");
        router.learnConsents();
        assertEquals(List.of(), router.studyFoldClashes(), "the second consent has an invalid name: no partition, no clash");
    }

    @Test
    void foldStudyDefinition() {
        assertEquals("phs999999", ConsentRouter.foldStudy(" PHS 999999" + FFFD + ".c1"));
        assertEquals("phs999999", ConsentRouter.foldStudy("phs999999"));
        assertEquals("", ConsentRouter.foldStudy(".c1"));
        assertEquals("tutorial-biolincc_test", ConsentRouter.foldStudy("Tutorial-BioLINCC_Test"));
        assertEquals("phs000000", ConsentRouter.foldStudy("phs000000.c1.x"));
    }

    // ---- the \_consents\ subtree -----------------------------------------------------------------------------------------------

    @Test
    void rowsUnderTheConsentsNodeThatAreNoConsentsRowsStayGlobalAndAreCounted() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\x\\,9,unrelated,", "1,\\_consents\\y\\z\\,,,", "1,\\Global\\G\\,1,,",
            "1,\\_consents\\x\\,,phs999999.c1,"
        );

        assertEquals(3, counts.consentsSubtreeRowsNotConsents());
        assertEquals(Map.of("phs000000.c1", 5L), counts.rowsPerPartition(), "all stay global: the partition directory is what authorizes");
        assertEquals(0, counts.consentRowsRejected());
        assertTrue(counts.balanced());
    }

    @Test
    void theSummaryShowsTheSubtreeCountAndTheInvalidStudyNodes() throws Exception {
        Path input = writeInput("subtree.csv", "1,\\_consents\\,,phs000000.c1,0\n1,\\_consents\\x\\,,unrelated,\n");
        Path base = newBase("subtree");

        assertEquals(0, run(base, props(input)));

        assertEquals(1, summary(base).get("routing").get("consentsSubtreeRowsNotConsents").asLong());
    }

    // ---- vectors v4 --------------------------------------------------------------------------------------------------------------

    @Test
    void theV4VectorFilesMatchTheirChecksumsAndUseTestAccessionsOnly() throws Exception {
        List<String> lines = resource(V4, "SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(4, lines.size());
        for (String line : lines) {
            String[] parts = line.split("  ", 2);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(V4, parts[1]).getBytes(StandardCharsets.UTF_8));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
        String text = resource(V4, INPUT) + resource(V4, COLLISION) + resource(V4, "expected-partitions.csv");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)phs[0-9]{6}").matcher(text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group().toLowerCase());
        }
        assertEquals(Set.of("phs000000", "phs999999", "phs123456"), found);
    }

    @Test
    void theV4VectorRoutesExactlyAsTheIndependentScriptDerivedIt() throws Exception {
        Path input = writeInput("v4.csv", resource(V4, INPUT));
        Path routed = tempDir.resolve("routed-v4");
        ConsentRouter router = new ConsentRouter(input, "test");
        router.learnConsents();
        ConsentRouter.Counts counts;
        try (FailureSink sink = new FailureSink(tempDir.resolve("v4-failures.jsonl"))) {
            counts = router.route(routed, sink);
        }

        Map<String, List<String>> expected = new TreeMap<>();
        for (String line : resource(V4, "expected-partitions.csv").lines().toList()) {
            List<String> fields = new ArrayList<>();
            org.apache.commons.csv.CSVFormat.DEFAULT.parse(new java.io.StringReader(line)).getRecords().get(0).forEach(fields::add);
            expected.computeIfAbsent(fields.get(0), k -> new ArrayList<>()).add(String.join("|", fields.subList(1, fields.size())));
        }
        assertEquals(expected.keySet(), counts.rowsPerPartition().keySet());
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            List<String> actual = new ArrayList<>();
            for (String line : Files.readAllLines(routed.resolve(entry.getKey() + "-allConcepts.csv"))) {
                actual.add(String.join("|", ConsentRouter.parseLine(line)));
            }
            assertEquals(entry.getValue().stream().sorted().toList(), actual.stream().sorted().toList(), entry.getKey());
        }
        JsonNode want = JSON.readTree(resource(V4, "expected-counts.json"));
        assertEquals(want.get("rowsRead").asLong(), counts.rowsRead());
        assertEquals(want.get("rowsRouted").asLong(), counts.rowsRouted());
        assertEquals(want.get("studyRows").asLong(), counts.studyRows());
        assertEquals(want.get("globalRows").asLong(), counts.globalRows());
        assertEquals(want.get("noConsentStudyRows").asLong(), counts.noConsentStudyRows());
        assertEquals(want.get("noConsentGlobalRows").asLong(), counts.noConsentGlobalRows());
        assertEquals(want.get("unroutableRows").asLong(), counts.unroutableRows());
        assertEquals(want.get("invalidStudyNodeRows").asLong(), counts.unroutableByReason().get("INVALID_STUDY_NODE"));
        assertEquals(want.get("consentRowsRejected").asLong(), counts.consentRowsRejected());
        assertEquals(want.get("linesWritten").asLong(), counts.linesWritten());
        assertEquals(want.get("patientsWithConsents").asLong(), counts.patientsWithConsents());
        assertEquals(want.get("consentsSubtreeRowsNotConsents").asLong(), counts.consentsSubtreeRowsNotConsents());
        List<String> without = new ArrayList<>();
        want.get("studyNodesWithoutConsents").forEach(n -> without.add(n.asText()));
        assertEquals(without, counts.studyNodesWithoutConsents());
        Map<String, Long> dropped = new TreeMap<>();
        want.get("invalidConsentNamesDropped").forEach(n -> dropped.put(n.get("value").asText(), n.get("rows").asLong()));
        Map<String, Long> actualDropped = new TreeMap<>();
        counts.invalidConsentNames().forEach(d -> actualDropped.put(d.value(), d.rows()));
        assertEquals(dropped, actualDropped);
        assertTrue(counts.balanced());
        assertEquals(want.get("linesWritten").asLong(), RoutedInvariant.assertHolds(input, routed));
    }

    @Test
    void v4RunsRefusedAtDefaultLimitsAndLoadsWithoutTheUnroutableStudyRows() throws Exception {
        Path input = writeInput("v4.csv", resource(V4, INPUT));

        Path refused = newBase("v4-default");
        assertEquals(1, run(refused, props(input)));
        assertEquals("failed", summary(refused).get("status").asText());
        String error = summary(refused).get("error").asText();
        assertTrue(error.contains("6 row(s) could not be routed {INVALID_STUDY_NODE=6}"), error);
        assertEquals(Set.of(), subdirectories(refused), "nothing was loaded");

        Path base = newBase("v4-raised");
        assertEquals(0, run(base, props(input, "picsure.loader.maxRejectedRows", "6", "picsure.loader.keepRouted", "true")));

        JsonNode routing = summary(base).get("routing");
        assertEquals("ok", summary(base).get("status").asText());
        assertEquals(6, routing.get("unroutableByReason").get("INVALID_STUDY_NODE").asLong());
        assertEquals(2, routing.get("consentsSubtreeRowsNotConsents").asLong());
        assertEquals(Set.of("phs000000.c1", "PHS999999.c1"), subdirectories(base));
        Path routed = CombinedInputTest.spoolOf(base).resolve(".routed");
        assertEquals(21, RoutedInvariant.assertHolds(input, routed));
        String main = Files.readString(routed.resolve("phs000000.c1-allConcepts.csv"));
        assertFalse(main.contains("\\phs999999\\"), "no row of the study phs999999 in phs000000.c1");
        assertFalse(main.contains("\\PHS999999\\"));
        assertTrue(Files.readString(routed.resolve("PHS999999.c1-allConcepts.csv")).contains("\\PHS999999\\X\\"));
        List<String> reasons = new ArrayList<>();
        for (String line : Files.readAllLines(base.resolve("routing-failures.jsonl"))) {
            reasons.add(JSON.readTree(line).get("reasonCode").asText());
        }
        assertEquals(6, reasons.stream().filter("INVALID_STUDY_NODE"::equals).count());
    }

    @Test
    void v4WithTheCaseCollisionAppendedIsRefused() throws Exception {
        Path input = writeInput("v4-collision.csv", resource(V4, INPUT) + resource(V4, COLLISION).lines().skip(1).collect(Collectors.joining("\n")) + "\n");
        Path base = newBase("v4-collision");

        assertEquals(2, run(base, props(input, "picsure.loader.maxRejectedRows", "1000")));
        assertEquals("refused", summary(base).get("status").asText());
    }

    // ---- the invariant checks what the router could get wrong -------------------------------------------------------------

    @Test
    void theInvariantCatchesTheReviewersLeakInARoutedDirectory() throws Exception {
        Path input = writeInput(
            "leak.csv", "1,\\_consents\\,,phs000000.c1,0\n1,\\_consents\\,,phs999999" + FFFD + ".c1,0\n1,\\phs999999\\X\\,2,,\n"
        );
        Path routed = tempDir.resolve("leak-routed");
        Files.createDirectories(routed);
        Files.writeString(
            routed.resolve("phs000000.c1-allConcepts.csv"),
            "\"1\",\"\\_consents\\\",\"\",\"phs000000.c1\",\"0\"\n\"1\",\"\\phs999999\\X\\\",\"2\",\"\",\"\"\n"
        );

        AssertionError error = assertThrows(AssertionError.class, () -> RoutedInvariant.assertHolds(input, routed));
        assertTrue(error.getMessage().contains("routed as a global row"), error.getMessage());
    }

    @Test
    void theInvariantChecksTheNormalizedNodeAndCatchesAPartitionWithoutAGrant() throws Exception {
        Path input = writeInput("norm.csv", "1,\\_consents\\,,phs000000.c1,0\n1,\\_consents\\,,phs999999.c1,0\n");
        Path routed = tempDir.resolve("norm-routed");
        Files.createDirectories(routed);
        // a node with blanks and U+FFFD: the loader stores \phs999999\..., which belongs to phs999999.c1 only
        Files.writeString(routed.resolve("phs000000.c1-allConcepts.csv"), "\"1\",\"\\ phs999" + FFFD + "999 \\X\\\",\"2\",\"\",\"\"\n");
        assertThrows(AssertionError.class, () -> RoutedInvariant.assertHolds(input, routed));

        Path granted = tempDir.resolve("grant-routed");
        Files.createDirectories(granted);
        Files.writeString(granted.resolve("phs000000.c1-allConcepts.csv"), "\"2\",\"\\Global\\G\\\",\"2\",\"\",\"\"\n"); // patient 2: no grant
        AssertionError error = assertThrows(AssertionError.class, () -> RoutedInvariant.assertHolds(input, granted));
        assertTrue(error.getMessage().contains("no valid canonical consent"), error.getMessage());
    }
}
