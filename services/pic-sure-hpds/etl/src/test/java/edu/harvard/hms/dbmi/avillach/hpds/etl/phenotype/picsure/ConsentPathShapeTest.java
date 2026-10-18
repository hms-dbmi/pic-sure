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
 * Review round 5 of the combined input. (1) Proposed contract rules R1 (the text before the first delimiter is empty, else
 * {@code MALFORMED_CONCEPT_PATH}) and R2 (the study node is not empty, else {@code INVALID_STUDY_NODE}): never a global row. (2) Study spellings fold by code point, so a
 * supplementary format character (tag characters, category Cf) is removed too. (3) A planned partition name that differs only by case from
 * an entry of the base refuses the run. Vectors in {@code consent-vectors/v5}; test accessions only: phs000000, phs999999, phs123456.
 */
class ConsentPathShapeTest {

    private static final String KEY = "1111111111111111";
    private static final String V5 = "/consent-vectors/v5/";
    private static final String INPUT = "combined-allConcepts-input.csv";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TAG = new String(Character.toChars(0xE0020));

    @TempDir
    Path tempDir;

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static String resource(String dir, String name) throws IOException {
        try (InputStream in = ConsentPathShapeTest.class.getResourceAsStream(dir + name)) {
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

    // ---- 1: the shape of the path (R1, R2) -----------------------------------------------------------------------------------

    @Test
    void r1APathWithTextBeforeTheFirstDelimiterIsMalformedAndNotStoredInAnyPartition() throws Exception {
        for (String path : List.of("phs999999\\X\\", "  \\Global\\G\\", "x\\phs999999\\X\\", "\uFFFD\\phs999999\\X\\", "phs999999")) {
            ConsentRouter.Counts counts = route(
                "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,phs999999.c1,0", "1," + path + ",777,,", "1,\\Global\\G\\,1,,"
            );

            assertEquals(Map.of("MALFORMED_CONCEPT_PATH", 1L), counts.unroutableByReason(), path);
            assertEquals(1, counts.unroutableCrashClassRows(), "counts against maxRejectedRows");
            for (String partition : List.of("phs000000.c1", "phs999999.c1")) {
                assertFalse(partitionText(lastRouted, partition).contains("777"), partition + " " + path);
            }
            assertTrue(counts.balanced());
            RoutedInvariant.assertHolds(lastInput, lastRouted);
        }
    }

    @Test
    void r2AnEmptyStudyNodeIsInvalidAndNotAGlobalRow() throws Exception {
        for (String path : List.of("\\\\phs999999\\X\\", "\\\\\\phs999999\\X\\", "\\ \\phs999999\\X\\", "\\\uFFFD\\phs999999\\X\\", "\\", "", "µµX")) {
            ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,phs999999.c1,0", "1," + path + ",777,,");

            assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason(), path);
            assertEquals(2, counts.globalRows(), "only the consents rows are global: " + path);
            assertFalse(partitionText(lastRouted, "phs000000.c1").contains("777"), path);
            RoutedInvariant.assertHolds(lastInput, lastRouted);
        }
    }

    @Test
    void theShapeRulesTouchNothingElse() throws Exception {
        // a mu as the delimiter, a study id at depth 2 and a global at depth 1 stay routable
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,µGlobalµB\\,2,,", "1,\\_studies_consents\\phs999999\\Y\\,3,,",
            "1,\\Global\\phs999999\\Y\\,4,,", "1,\\phs000000\\Demographics\\Age\\,40,,"
        );

        assertEquals(0, counts.unroutableRows());
        assertEquals(5, counts.rowsRouted());
        assertEquals(List.of(), counts.studyNodesWithoutConsents(), "a study id at depth 2 is no study node");
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void aConsentsLikePathWithTextBeforeTheFirstDelimiterGrantsNothing() throws Exception {
        for (String path : List.of("_consents\\", "_consents", "_consents\\\\", "\\\\_consents\\", "x\\_consents\\")) {
            ConsentRouter.Counts counts = route("1," + path + ",,phs000000.c1,0", "1,\\Global\\G\\,1,,");

            assertEquals(0, counts.patientsWithConsents(), path);
            assertEquals(0, counts.rowsRouted(), path);
            assertEquals(1, counts.noConsentGlobalRows(), path);
            assertEquals(0, counts.linesWritten(), path);
        }
        // the consents-like row is not canonical, so it is rejected or unroutable, never routed
        ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,0", "1,_consents\\phs999999.c1\\,,phs999999.c1,0", "1,\\Global\\G\\,1,,");
        assertEquals(1, counts.patientsWithConsents());
        assertEquals(Map.of("MALFORMED_CONCEPT_PATH", 1L), counts.unroutableByReason());
        assertFalse(partitionText(lastRouted, "phs000000.c1").contains("phs999999"));
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void theInvariantCatchesARoutedRowThatBreaksR1OrR2() throws Exception {
        Path input = writeInput("shape.csv", "1,\\_consents\\,,phs000000.c1,0\n");
        for (String path : List.of("phs999999\\X\\", "  \\Global\\G\\", "\\\\phs999999\\X\\", "µµphs999999µX")) {
            Path routed = tempDir.resolve("shape-routed-" + Math.abs(path.hashCode()));
            Files.createDirectories(routed);
            Files.writeString(
                routed.resolve("phs000000.c1-allConcepts.csv"),
                "\"1\",\"\\_consents\\\",\"\",\"phs000000.c1\",\"0\"\n\"1\",\"" + path + "\",\"2\",\"\",\"\"\n"
            );
            AssertionError error = assertThrows(AssertionError.class, () -> RoutedInvariant.assertHolds(input, routed), path);
            assertTrue(error.getMessage().contains("(R1)") || error.getMessage().contains("(R2)"), error.getMessage());
        }
    }

    // ---- 2: supplementary format characters ------------------------------------------------------------------------------------

    @Test
    void foldStudyRemovesSupplementaryFormatCharactersLikeTheOnesOfTheBasicPlane() {
        assertEquals("phs999999", ConsentRouter.foldStudy("phs999999" + TAG));
        assertEquals("phs999999", ConsentRouter.foldStudy("PHS" + TAG + "999999" + new String(Character.toChars(0xE007F)) + ".c1"));
        assertEquals("phs999999", ConsentRouter.foldStudy("phs999999​")); // zero width space, BMP format character
        assertEquals("phs999999", ConsentRouter.foldStudy(new String(Character.toChars(0xE0001)) + "phs999999"));
        // a supplementary letter is kept whole (not split into surrogates) and lower-cased
        String deseret = new String(Character.toChars(0x10400)); // DESERET CAPITAL LETTER LONG I
        assertEquals(new String(Character.toChars(0x10428)), ConsentRouter.foldStudy(deseret));
    }

    @Test
    void aStudyNodeWithATagCharacterIsAnotherSpellingOfTheStudyAndNeverGlobal() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,phs999999.c1,0", "1,\\phs999999" + TAG + "\\X\\,2,,",
            "1,\\" + TAG + "phs999999\\X\\,3,,", "1,\\phs999999\\X\\,4,,"
        );

        assertEquals(Map.of("INVALID_STUDY_NODE", 2L), counts.unroutableByReason());
        assertEquals(1, counts.studyRows());
        assertFalse(partitionText(lastRouted, "phs000000.c1").contains("\\X\\"), "no row of the study in the other partition");
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    // ---- vectors v5 -------------------------------------------------------------------------------------------------------------

    @Test
    void theV5VectorFilesMatchTheirChecksumsAndUseTestAccessionsOnly() throws Exception {
        List<String> lines = resource(V5, "SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(3, lines.size());
        for (String line : lines) {
            String[] parts = line.split("  ", 2);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(V5, parts[1]).getBytes(StandardCharsets.UTF_8));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
        String text = resource(V5, INPUT) + resource(V5, "expected-partitions.csv");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)phs[0-9]{6}").matcher(text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group().toLowerCase());
        }
        assertEquals(Set.of("phs000000", "phs999999", "phs123456"), found);
        assertTrue(resource(V5, INPUT).contains(TAG), "the vector holds the tag character row");
    }

    @Test
    void theV5VectorRoutesExactlyAsTheIndependentScriptDerivedIt() throws Exception {
        Path input = writeInput("v5.csv", resource(V5, INPUT));
        Path routed = tempDir.resolve("routed-v5");
        ConsentRouter router = new ConsentRouter(input, "test");
        router.learnConsents();
        ConsentRouter.Counts counts;
        try (FailureSink sink = new FailureSink(tempDir.resolve("v5-failures.jsonl"))) {
            counts = router.route(routed, sink);
        }

        Map<String, List<String>> expected = new TreeMap<>();
        for (String line : resource(V5, "expected-partitions.csv").lines().toList()) {
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
        JsonNode want = JSON.readTree(resource(V5, "expected-counts.json"));
        assertEquals(want.get("rowsRead").asLong(), counts.rowsRead());
        assertEquals(want.get("rowsRouted").asLong(), counts.rowsRouted());
        assertEquals(want.get("studyRows").asLong(), counts.studyRows());
        assertEquals(want.get("globalRows").asLong(), counts.globalRows());
        assertEquals(want.get("noConsentStudyRows").asLong(), counts.noConsentStudyRows());
        assertEquals(want.get("noConsentGlobalRows").asLong(), counts.noConsentGlobalRows());
        assertEquals(want.get("unroutableRows").asLong(), counts.unroutableRows());
        assertEquals(want.get("invalidStudyNodeRows").asLong(), counts.unroutableByReason().get("INVALID_STUDY_NODE"));
        assertEquals(want.get("malformedConceptPathRows").asLong(), counts.unroutableByReason().get("MALFORMED_CONCEPT_PATH"));
        assertEquals(want.get("consentRowsRejected").asLong(), counts.consentRowsRejected());
        assertEquals(want.get("linesWritten").asLong(), counts.linesWritten());
        assertEquals(want.get("patientsWithConsents").asLong(), counts.patientsWithConsents());
        assertEquals(want.get("consentsSubtreeRowsNotConsents").asLong(), counts.consentsSubtreeRowsNotConsents());
        assertTrue(counts.balanced());
        assertEquals(want.get("linesWritten").asLong(), RoutedInvariant.assertHolds(input, routed));
    }

    @Test
    void v5RunsRefusedAtDefaultLimitsAndLoadsWithoutTheMalformedRows() throws Exception {
        Path input = writeInput("v5.csv", resource(V5, INPUT));

        Path refused = newBase("v5-default");
        assertEquals(1, run(refused, props(input)));
        String error = summary(refused).get("error").asText();
        assertTrue(error.contains("10 row(s) could not be routed"), error);
        assertTrue(error.contains("MALFORMED_CONCEPT_PATH=2") && error.contains("INVALID_STUDY_NODE=8"), error);
        assertEquals(Set.of(), subdirectories(refused), "nothing was loaded");

        Path base = newBase("v5-raised");
        assertEquals(0, run(base, props(input, "picsure.loader.maxRejectedRows", "10", "picsure.loader.keepRouted", "true")));
        assertEquals(2, summary(base).get("routing").get("unroutableByReason").get("MALFORMED_CONCEPT_PATH").asLong());
        assertEquals(Set.of("phs000000.c1", "PHS999999.c1"), subdirectories(base));
        Path routed = CombinedInputTest.spoolOf(base).resolve(".routed");
        assertEquals(27, RoutedInvariant.assertHolds(input, routed));
        for (String partition : List.of("phs000000.c1", "PHS999999.c1")) {
            String text = Files.readString(routed.resolve(partition + "-allConcepts.csv"));
            assertFalse(text.contains("phs999999\\X"), partition);
            assertFalse(text.contains(TAG), partition);
            assertFalse(text.contains("\"_consents\\\""), "the consents-like row without a leading delimiter: " + partition);
        }
        List<String> reasons = new ArrayList<>();
        for (String line : Files.readAllLines(base.resolve("routing-failures.jsonl"))) {
            reasons.add(JSON.readTree(line).get("reasonCode").asText());
        }
        assertEquals(2, reasons.stream().filter("MALFORMED_CONCEPT_PATH"::equals).count());
    }

    // ---- 3: a partition name that differs by case from an entry of the base ---------------------------------------------------------

    @Test
    void aPlannedPartitionThatDiffersOnlyByCaseFromAnExistingEntryIsRefusedBeforeAnyWork() throws Exception {
        Path input = writeInput("small.csv", "1,\\_consents\\,,phs000000.c1,0\n1,\\Global\\G\\,1,,\n");
        Path base = newBase("existing-case");
        Files.createDirectories(base.resolve("PHS000000.C1"));
        Files.writeString(base.resolve("PHS000000.C1").resolve("marker"), "old");

        assertEquals(2, run(base, props(input)));
        assertEquals("refused", summary(base).get("status").asText());
        String error = summary(base).get("error").asText();
        assertTrue(error.contains("phs000000.c1 -> PHS000000.C1"), error);
        assertEquals(Set.of("PHS000000.C1"), subdirectories(base), "nothing was written next to it, nothing written into it");
        assertEquals("old", Files.readString(base.resolve("PHS000000.C1").resolve("marker")));
        try (Stream<Path> files = Files.list(base.resolve("PHS000000.C1"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void anExactMatchIsNotACaseClash() throws Exception {
        assertEquals(List.of(), PICSURELoader.existingCaseClashes(newBase("exact"), List.of("encryption_key", "other")), "the very name is not a clash");
        Path base = newBase("exact-dir");
        Files.createDirectories(base.resolve("phs000000.c1"));
        assertEquals(List.of(), PICSURELoader.existingCaseClashes(base, List.of("phs000000.c1")));
        assertEquals(List.of("phs000000.c1 -> Phs000000.C1"), PICSURELoader.existingCaseClashes(newBaseWith("Phs000000.C1"), List.of("phs000000.c1")));
    }

    private Path newBaseWith(String child) throws IOException {
        Path base = newBase("with-" + child);
        Files.createDirectories(base.resolve(child));
        return base;
    }
}
