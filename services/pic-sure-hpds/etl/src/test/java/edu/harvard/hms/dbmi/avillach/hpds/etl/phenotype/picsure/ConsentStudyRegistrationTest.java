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
 * Review round 6 of the combined input: a path that mentions {@code _consents} and breaks R1 or R2 might be a consents row, so it is a
 * rejected consents row (grants nothing, registers the study of its text value, counts against {@code maxRejectedConsentRows}), never a
 * data row. Raising a limit must stay safe: the well-formed rows of a study named only by such a row are never global. Vectors in
 * {@code consent-vectors/v6}; test accessions only: phs000000, phs999999, phs123456.
 */
class ConsentStudyRegistrationTest {

    private static final String KEY = "1111111111111111";
    private static final String V6 = "/consent-vectors/v6/";
    private static final String INPUT = "combined-allConcepts-input.csv";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static String resource(String dir, String name) throws IOException {
        try (InputStream in = ConsentStudyRegistrationTest.class.getResourceAsStream(dir + name)) {
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

    private ConsentRouter.Counts route(Path input) throws IOException {
        lastInput = input;
        lastRouted = tempDir.resolve("routed-" + input.getFileName());
        ConsentRouter router = new ConsentRouter(input, "test");
        router.learnConsents();
        try (FailureSink sink = new FailureSink(tempDir.resolve("failures-" + input.getFileName() + ".jsonl"))) {
            return router.route(lastRouted, sink);
        }
    }

    private ConsentRouter.Counts route(String... lines) throws IOException {
        return route(writeInput("small.csv", String.join("\n", lines) + "\n"));
    }

    private static String partitionText(Path routed, String partition) throws IOException {
        Path file = routed.resolve(partition + "-allConcepts.csv");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    // ---- the rule ------------------------------------------------------------------------------------------------------------

    @Test
    void aConsentsLikeRowThatBreaksR1OrR2IsARejectedConsentsRowAndRegistersItsStudy() throws Exception {
        // R1: text before the first delimiter. R2: empty study node, or a node that normalization changes. Other case, U+FFFD inside.
        Map<String, String> paths = new TreeMap<>();
        paths.put("_consents\\", "MALFORMED_CONCEPT_PATH");
        paths.put("_consents", "MALFORMED_CONCEPT_PATH");
        paths.put("x\\_consents\\", "MALFORMED_CONCEPT_PATH");
        paths.put("_CONSENTS\\", "MALFORMED_CONCEPT_PATH");
        paths.put("x\\_Consents\\", "MALFORMED_CONCEPT_PATH");
        paths.put("_cons�ents\\", "MALFORMED_CONCEPT_PATH");
        paths.put("�_consents\\", "MALFORMED_CONCEPT_PATH");
        paths.put("\\\\_consents\\", "INVALID_STUDY_NODE");
        paths.put("\\ \\_consents\\", "INVALID_STUDY_NODE");
        paths.put("\\�\\_consents\\", "INVALID_STUDY_NODE");
        paths.put("\\\\_CONSENTS\\", "INVALID_STUDY_NODE");
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            String label = entry.getKey().replace("�", "<FFFD>");
            ConsentRouter.Counts counts = route(
                "1,\\_consents\\,,phs000000.c1,0", "1," + entry.getKey() + ",,phs999999.c1,0", "1,\\phs999999\\X\\,777,,",
                "1,\\Global\\G\\,1,,"
            );

            assertEquals(Map.of(entry.getValue(), 1L), counts.consentRowsRejectedByReason(), label);
            assertEquals(1, counts.consentRowsRejected(), label);
            assertEquals(0, counts.unroutableRows(), label);
            assertEquals(List.of("phs999999"), counts.studiesOnlyInRejectedConsentRows(), label);
            assertEquals(1, counts.noConsentStudyRows(), "the row of the registered study is dropped, not global: " + label);
            assertFalse(partitionText(lastRouted, "phs000000.c1").contains("777"), label);
            assertTrue(counts.balanced(), label);
            RoutedInvariant.assertHolds(lastInput, lastRouted);
        }
    }

    @Test
    void theRuleDoesNotTouchRowsThatMerelyMentionConsentsDeeperInThePath() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_studies_consents\\phs999999\\Y\\,3,,", "1,\\Global\\_consents\\Z\\,4,,",
            "1,\\_CONSENTS\\Q\\,5,,"
        );

        assertEquals(0, counts.consentRowsRejected());
        assertEquals(0, counts.unroutableRows());
        assertEquals(4, counts.globalRows());
        assertEquals(List.of(), counts.studiesOnlyInRejectedConsentRows());
    }

    @Test
    void theRowNeverGrantsAndIsDecidedBeforeTheFieldCountAndThePatient() throws Exception {
        // a short row, a row without a patient: both passes judge them as consents-like rows, so the passes agree
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,_consents\\,phs999999.c1,0", ",_consents\\,,phs999999.c1,0", "x,x\\_consents\\,,phs999999.c1,0",
            "1,\\phs999999\\X\\,777,,"
        );

        assertEquals(1, counts.patientsWithConsents());
        assertEquals(3, counts.consentRowsRejected());
        assertEquals(0, counts.unroutableRows());
        assertEquals(List.of("phs999999"), counts.studiesOnlyInRejectedConsentRows());
        assertTrue(counts.balanced());
        RoutedInvariant.assertHolds(lastInput, lastRouted);
    }

    @Test
    void theInvariantCatchesAStudyRowOfARegisteredStudyRoutedAsGlobal() throws Exception {
        Path input = writeInput("inv.csv", "1,\\_consents\\,,phs000000.c1,0\n1,_consents\\,,phs999999.c1,0\n");
        Path routed = tempDir.resolve("inv-routed");
        Files.createDirectories(routed);
        Files.writeString(
            routed.resolve("phs000000.c1-allConcepts.csv"),
            "\"1\",\"\\_consents\\\",\"\",\"phs000000.c1\",\"0\"\n\"1\",\"\\phs999999\\X\\\",\"2\",\"\",\"\"\n"
        );
        AssertionError error = assertThrows(AssertionError.class, () -> RoutedInvariant.assertHolds(input, routed));
        assertTrue(error.getMessage().contains("(b)") || error.getMessage().contains("(a)"), error.getMessage());
    }

    // ---- vectors v6 -------------------------------------------------------------------------------------------------------------

    @Test
    void theV6VectorFilesMatchTheirChecksumsAndUseTestAccessionsOnly() throws Exception {
        List<String> lines = resource(V6, "SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(3, lines.size());
        for (String line : lines) {
            String[] parts = line.split("  ", 2);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(V6, parts[1]).getBytes(StandardCharsets.UTF_8));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("(?i)phs[0-9]{6}").matcher(resource(V6, INPUT) + resource(V6, "expected-partitions.csv"));
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group().toLowerCase());
        }
        assertEquals(Set.of("phs000000", "phs999999", "phs123456"), found);
    }

    @Test
    void theV6VectorRoutesExactlyAsTheIndependentScriptDerivedIt() throws Exception {
        Path input = writeInput("v6.csv", resource(V6, INPUT));
        ConsentRouter.Counts counts = route(input);

        Map<String, List<String>> expected = new TreeMap<>();
        for (String line : resource(V6, "expected-partitions.csv").lines().toList()) {
            List<String> fields = new ArrayList<>();
            org.apache.commons.csv.CSVFormat.DEFAULT.parse(new java.io.StringReader(line)).getRecords().get(0).forEach(fields::add);
            expected.computeIfAbsent(fields.get(0), k -> new ArrayList<>()).add(String.join("|", fields.subList(1, fields.size())));
        }
        assertEquals(expected.keySet(), counts.rowsPerPartition().keySet());
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            List<String> actual = new ArrayList<>();
            for (String line : Files.readAllLines(lastRouted.resolve(entry.getKey() + "-allConcepts.csv"))) {
                actual.add(String.join("|", ConsentRouter.parseLine(line)));
            }
            assertEquals(entry.getValue().stream().sorted().toList(), actual.stream().sorted().toList(), entry.getKey());
        }
        JsonNode want = JSON.readTree(resource(V6, "expected-counts.json"));
        assertEquals(want.get("rowsRead").asLong(), counts.rowsRead());
        assertEquals(want.get("rowsRouted").asLong(), counts.rowsRouted());
        assertEquals(want.get("studyRows").asLong(), counts.studyRows());
        assertEquals(want.get("globalRows").asLong(), counts.globalRows());
        assertEquals(want.get("noConsentStudyRows").asLong(), counts.noConsentStudyRows());
        assertEquals(want.get("noConsentGlobalRows").asLong(), counts.noConsentGlobalRows());
        assertEquals(want.get("unroutableRows").asLong(), counts.unroutableRows());
        assertEquals(want.get("consentRowsRejected").asLong(), counts.consentRowsRejected());
        assertEquals(want.get("consentRowsRejectedByReason").get("MALFORMED_CONCEPT_PATH").asLong(), counts.consentRowsRejectedByReason().get("MALFORMED_CONCEPT_PATH"));
        assertEquals(want.get("consentRowsRejectedByReason").get("INVALID_STUDY_NODE").asLong(), counts.consentRowsRejectedByReason().get("INVALID_STUDY_NODE"));
        assertEquals(want.get("linesWritten").asLong(), counts.linesWritten());
        assertEquals(want.get("patientsWithConsents").asLong(), counts.patientsWithConsents());
        List<String> studiesOnly = new ArrayList<>();
        want.get("studiesOnlyInRejectedConsentRows").forEach(n -> studiesOnly.add(n.asText()));
        assertEquals(studiesOnly, counts.studiesOnlyInRejectedConsentRows());
        assertTrue(counts.balanced());
        assertEquals(want.get("linesWritten").asLong(), RoutedInvariant.assertHolds(input, lastRouted));
    }

    @Test
    void v6IsRefusedAtDefaultLimitsAndWithRaisedLimitsTheRegisteredStudiesRowsAreDroppedNotGlobal() throws Exception {
        Path input = writeInput("v6.csv", resource(V6, INPUT));

        Path refused = newBase("v6-default");
        assertEquals(1, run(refused, props(input)));
        assertTrue(summary(refused).get("error").asText().contains("3 row(s) of the consents variable may not grant"));
        assertEquals(Set.of(), subdirectories(refused), "nothing was loaded");

        Path base = newBase("v6-raised");
        assertEquals(0, run(base, props(input, "picsure.loader.maxRejectedConsentRows", "3", "picsure.loader.keepRouted", "true")));
        assertEquals(Set.of("phs000000.c1"), subdirectories(base));
        Path routed = CombinedInputTest.spoolOf(base).resolve(".routed");
        assertEquals(8, RoutedInvariant.assertHolds(input, routed));
        String text = Files.readString(routed.resolve("phs000000.c1-allConcepts.csv"));
        assertFalse(text.contains("phs999999\\X"), "the well-formed row of the study named only by the rejected row is dropped");
        assertFalse(text.contains("phs123456\\Labs"), text);
        JsonNode routing = summary(base).get("routing");
        assertEquals(2, routing.get("noConsentStudyRows").asLong());
        assertEquals(3, routing.get("consentRowsRejected").asLong());
    }

    // ---- the v5 vector, one row reclassified ------------------------------------------------------------------------------------------

    @Test
    void theV5RowOfPatient7IsNowARejectedConsentsRowInsteadOfAnUnroutableOne() throws Exception {
        Path input = writeInput("v5.csv", resource("/consent-vectors/v5/", INPUT));
        ConsentRouter.Counts counts = route(input);

        JsonNode want = JSON.readTree(resource("/consent-vectors/v5/", "expected-counts.json"));
        assertEquals(want.get("unroutableRows").asLong() - 1, counts.unroutableRows());
        assertEquals(want.get("malformedConceptPathRows").asLong() - 1, counts.unroutableByReason().get("MALFORMED_CONCEPT_PATH"));
        assertEquals(want.get("consentRowsRejected").asLong() + 1, counts.consentRowsRejected());
        assertEquals(Map.of("MALFORMED_CONCEPT_PATH", 1L), counts.consentRowsRejectedByReason());
        assertEquals(want.get("linesWritten").asLong(), counts.linesWritten());
        assertTrue(counts.balanced());
    }

    // ---- a planned partition name against the base: full case fold ------------------------------------------------------------------

    @Test
    void aCaseClashIsFoundThroughAFullCaseFold() throws Exception {
        Path base = newBase("long-s");
        Files.createDirectories(base.resolve("phſ000000.c1")); // LATIN SMALL LETTER LONG S: upper-cases to 'S'
        assertEquals(List.of("phs000000.c1 -> phſ000000.c1"), PICSURELoader.existingCaseClashes(base, List.of("phs000000.c1")));
    }
}
