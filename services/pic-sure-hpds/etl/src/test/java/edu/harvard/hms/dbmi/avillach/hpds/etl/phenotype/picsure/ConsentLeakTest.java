package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review round 3 of the combined input: the last paths by which a consents row could open access. A rejected consents row still names its
 * study; a row that reads as the consents variable in the partition's loader but is not written exactly {@code \_consents\} grants nothing;
 * a consents row on a line that cannot be parsed ends the run; a spool directory that contains the base is refused. The vectors in
 * {@code consent-vectors/v3} were derived by an independent script (see their README); test accessions only: phs000000, phs999999,
 * phs123456.
 */
class ConsentLeakTest {

    private static final String KEY = "1111111111111111";
    private static final String V1 = "/consent-vectors/v1/";
    private static final String V2 = "/consent-vectors/v2/";
    private static final String V3 = "/consent-vectors/v3/";
    private static final String INPUT = "combined-allConcepts-input.csv";
    private static final String UNPARSABLE = "unparsable-consents-input.csv";
    private static final String EXPECTED = "expected-partitions.csv";
    private static final String COUNTS = "expected-counts.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static String resource(String dir, String name) throws IOException {
        try (InputStream in = ConsentLeakTest.class.getResourceAsStream(dir + name)) {
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

    /** Routes the lines (pass 1 and 2) and checks the invariant on the routed files. */
    private ConsentRouter.Counts route(String... lines) throws IOException {
        Path input = writeInput("small.csv", String.join("\n", lines) + "\n");
        return route(input, tempDir.resolve("small-routed"));
    }

    private ConsentRouter.Counts route(Path input, Path routed) throws IOException {
        ConsentRouter router = new ConsentRouter(input, "test");
        router.learnConsents();
        ConsentRouter.Counts counts;
        try (FailureSink sink = new FailureSink(tempDir.resolve(routed.getFileName() + "-failures.jsonl"))) {
            counts = router.route(routed, sink);
        }
        return counts;
    }

    private static List<String> reasons(Path jsonl) throws IOException {
        List<String> reasons = new ArrayList<>();
        for (String line : Files.readAllLines(jsonl)) {
            reasons.add(JSON.readTree(line).get("reasonCode").asText());
        }
        return reasons;
    }

    // ---- fix 1: a rejected consents row still names its study ----------------------------------------------------------------

    @Test
    void aRejectedConsentsRowMakesItsStudyKnownSoItsRowsAreDroppedNotGlobal() throws Exception {
        // the reviewer's example: patient 1 holds phs000000.c1; phs999999.c1 is rejected (a numeric nval)
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,5,phs999999.c1,0", "1,\\phs000000\\A\\,1,,", "1,\\phs999999\\X\\,2,,"
        );

        assertEquals(Map.of("phs000000.c1", 2L), counts.rowsPerPartition(), "the phs999999 row is not in phs000000.c1");
        assertEquals(1, counts.noConsentStudyRows(), "dropped as NO_CONSENT and counted");
        assertEquals(0, counts.noConsentGlobalRows());
        assertEquals(List.of(), counts.studyNodesWithoutConsents(), "it has a consents row, a rejected one: not a node without consents");
        assertEquals(List.of("phs999999"), counts.studiesOnlyInRejectedConsentRows());
        assertEquals(Map.of("NUMERIC_NVAL", 1L), counts.consentRowsRejectedByReason());
        assertTrue(counts.balanced());
        assertEquals(2, RoutedInvariant.assertHolds(tempDir.resolve("data/small.csv"), tempDir.resolve("small-routed")));
        assertEquals(
            List.of("CONSENT_ROW_REJECTED", "NO_CONSENT"), reasons(tempDir.resolve("small-routed-failures.jsonl")), "both are on record"
        );
    }

    @Test
    void theStudyIsKnownWhereverTheRejectedRowSitsInTheFile() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\phs999999\\X\\,2,,", "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,,phs999999.c1,soon", "1,\\phs999999\\X\\,3,,"
        );

        assertEquals(2, counts.noConsentStudyRows(), "the row before the rejected consents row is dropped too");
        assertEquals(Map.of("phs000000.c1", 1L), counts.rowsPerPartition());
    }

    @Test
    void everyKindOfRejectedRowThatCanBeParsedRegistersItsStudy() throws Exception {
        for (
            String consentsRow : List.of(
                "1,\\_consents\\,garbage,phs999999.c1,0", "1,\\_consents\\,7,phs999999.c1,0", "1,\\_consents\\,,phs999999.c1,notatime",
                "\" 1\",\\_consents\\,,phs999999.c1,0", "1,\\_consents\\,,phs999999.c1,0,extra", "1,\\_consents\\,,phs999999.c1",
                "1,\\_consents\\,phs999999.c1", "1,\\_consents\\ ,,phs999999.c1,0", "1,\\_consents,,phs999999.c1,0",
                "1,\\_consents\\phs999999.c1\\,,phs999999.c1,0", "1,\\_consents\\,,  phs999999.c1  ,notatime"
            )
        ) {
            ConsentRouter.Counts counts = route("2,\\_consents\\,,phs000000.c1,0", consentsRow, "2,\\phs999999\\X\\,2,,");

            assertEquals(1, counts.noConsentStudyRows(), consentsRow);
            assertEquals(Map.of("phs000000.c1", 1L), counts.rowsPerPartition(), consentsRow);
            assertEquals(List.of("phs999999"), counts.studiesOnlyInRejectedConsentRows(), consentsRow);
            assertEquals(1, counts.consentRowsRejected(), consentsRow);
        }
    }

    @Test
    void aRejectedRowWithoutTextValueNamesNoStudy() throws Exception {
        // an empty text value must not make the empty node (a path like \\A\) a study
        ConsentRouter.Counts counts =
            route("1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,5,,0", "1,\\_consents\\,5,   ,0", "1,\\Global\\A\\,1,,",
                "1,\\\\A\\,1,,");

        assertEquals(Map.of("NUMERIC_NVAL", 2L), counts.consentRowsRejectedByReason());
        assertEquals(List.of(), counts.studiesOnlyInRejectedConsentRows());
        assertEquals(0, counts.noConsentStudyRows());
        assertEquals(Map.of("INVALID_STUDY_NODE", 1L), counts.unroutableByReason(), "R2: the empty node is not a global row");
        assertEquals(Map.of("phs000000.c1", 2L), counts.rowsPerPartition(), "the global row is still routed");
    }

    @Test
    void aStudyThatHasNoConsentsRowAtAllStaysAGlobalVariableAndIsReported() throws Exception {
        // parity with the splitter, a separately tracked policy question: not changed here
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\_consents\\,5,phs123456.c1,0", "1,\\phs999999\\M\\BMI\\,22.5,,",
            "1,\\phs123456\\L\\,1,,"
        );

        assertEquals(List.of("phs999999"), counts.studyNodesWithoutConsents());
        assertEquals(List.of("phs123456"), counts.studiesOnlyInRejectedConsentRows());
        assertEquals(Map.of("phs000000.c1", 2L), counts.rowsPerPartition(), "the consents row and the phs999999 row; phs123456 is dropped");
        assertEquals(1, counts.noConsentStudyRows());
        assertEquals(2, counts.globalRows(), "the phs999999 row is global, besides the consents row");
    }

    @Test
    void aStudyWithAValidConsentElsewhereOnlyServesItsHolders() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs999999.c1,0", "2,\\_consents\\,,phs000000.c1,0", "2,\\_consents\\,5,phs999999.c1,0",
            "1,\\phs999999\\X\\,1,,", "2,\\phs999999\\X\\,2,,"
        );

        assertEquals(Map.of("phs000000.c1", 1L, "phs999999.c1", 2L), counts.rowsPerPartition());
        assertEquals(1, counts.noConsentStudyRows());
        assertEquals(List.of(), counts.studiesOnlyInRejectedConsentRows(), "patient 1 holds a valid consent of it");
    }

    @Test
    void anUnparsableConsentsLineRefusesTheRunWhateverTheLimitsAndBeforeAnyWork() throws Exception {
        Path input = writeInput(UNPARSABLE, resource(V3, UNPARSABLE));
        Map<String, String> raised =
            props(input, "picsure.loader.maxRejectedRows", "1000000", "picsure.loader.maxRejectedConsentRows", "1000000");

        Path defaults = newBase("unparsable-default");
        Path limits = newBase("unparsable-raised");
        assertEquals(2, run(defaults, props(input)));
        assertEquals(2, run(limits, raised));

        for (Path base : List.of(defaults, limits)) {
            JsonNode summary = summary(base);
            assertEquals("refused", summary.get("status").asText());
            String error = summary.get("error").asText();
            assertTrue(error.contains("mention the consents variable but cannot be parsed"), error);
            assertTrue(error.contains("[4]"), "the physical line of the input: " + error);
            assertTrue(error.contains("No limit tolerates this"), error);
            assertEquals(Set.of(), subdirectories(base));
            assertFalse(Files.exists(CombinedInputTest.spoolOf(base)), "no routed file was written");
            assertFalse(Files.exists(base.resolve("routing-failures.jsonl")));
        }
    }

    @Test
    void anUnparsableConsentsLineIsFoundWhetherOrNotItIsTheOnlyOne() throws Exception {
        Path input = writeInput(
            "two.csv", "1,\\_consents\\,,phs000000.c1,0\n3,µ_consentsµ,,\"phs000000.c1,0\n1,\"\\_con\uFFFDsents\\,,phs000000.c1,0\n"
        );
        ConsentRouter router = new ConsentRouter(input, "test");

        router.learnConsents();

        assertEquals(2, router.unparsableConsentRows(), "also with U+FFFD inside the name");
        assertEquals(List.of(2L, 3L), router.unparsableConsentLines());
    }

    // ---- fix 2: the loader's reading of the path -----------------------------------------------------------------------------

    @Test
    void onlyTheTwoCanonicalSpellingsMayGrant() {
        for (String path : List.of("\\_consents\\", "µ_consentsµ")) {
            assertEquals(ConsentRouter.PathForm.CANONICAL, ConsentRouter.pathForm(List.of("1", path, "", "phs000000.c1", "0")), path);
        }
        for (
            String path : List.of(
                "\\_consents\\ ", "\\_consents", "\\_consents\\\\", "  \\_consents\\", "\\_consents\\ \t", " \\ _consents \\",
                "µ_consents\\", "\\_consentsµ", "\\_cons\uFFFDents\\", "\\_consents\uFFFD\\", "\uFFFD\\_consents\\",
                "\\_consents\\phs999999.c1\\", "µ_consentsµphs999999.c1µ", "\\_consents\\ phs999999.c1 \\", "\\_consents\\phs999999.c1",
                "\t\\_consents\\"
            )
        ) {
            assertEquals(
                ConsentRouter.PathForm.NON_CANONICAL, ConsentRouter.pathForm(List.of("1", path, "", "phs999999.c1", "0")),
                path.replace("\uFFFD", "<FFFD>")
            );
        }
    }

    @Test
    void aPathThatTheLoaderDoesNotReadAsTheConsentsVariableIsNotAConsentsRow() {
        for (
            String path : List.of(
                "\\_consents\\x\\", "\\Foo\\_consents\\", "\\\\_consents\\", "\\_consents2\\", "\\phs000000\\A\\", "\u00a0\\_consents\\",
                "\\_consents\u00a0\\", "\\_Consents\\", "", "\\"
            )
        ) {
            assertEquals(ConsentRouter.PathForm.NOT_CONSENTS, ConsentRouter.pathForm(List.of("1", path, "", "phs999999.c1", "0")), path);
        }
        assertEquals(ConsentRouter.PathForm.NOT_CONSENTS, ConsentRouter.pathForm(List.of("1")));
        assertEquals(ConsentRouter.PathForm.NOT_CONSENTS, ConsentRouter.pathForm(null));
        // the segment equal to the text value is dropped only when it is the text value
        assertEquals(
            ConsentRouter.PathForm.NOT_CONSENTS,
            ConsentRouter.pathForm(List.of("1", "\\_consents\\phs999999.c1\\", "", "phs000000.c1", "0"))
        );
        assertEquals(
            ConsentRouter.PathForm.NON_CANONICAL,
            ConsentRouter.pathForm(List.of("1", "\\_consents\\phs999999.c1\\", "", " phs999999.c1 ", "0"))
        );
    }

    @Test
    void everyDoubtfulFormIsRejectedAsNonCanonicalInBothPassesAndNeverRoutedAsAGlobalRow() throws Exception {
        List<String> paths = List.of(
            "\\_consents\\ ", "\\_consents", "\\_consents\\\\", "  \\_consents\\", "\\_consents\\phs999999.c1\\", "µ_consents\\",
            "\\_cons\uFFFDents\\", "\\ _consents \\"
        );
        List<String> lines = new ArrayList<>(List.of("1,\\_consents\\,,phs000000.c1,0", "1,\\phs000000\\A\\,1,,"));
        for (String path : paths) {
            lines.add("1," + path + ",,phs999999.c1,0");
        }
        lines.add("1,\\phs999999\\X\\,2,,");
        lines.add("1,\\Global\\G\\,3,,");

        ConsentRouter.Counts counts = route(lines.toArray(String[]::new)); // both passes agree, or this throws InputChangedException

        assertEquals(Map.of("NON_CANONICAL_PATH", (long) paths.size()), counts.consentRowsRejectedByReason());
        assertEquals(
            Map.of("phs000000.c1", 3L), counts.rowsPerPartition(), "the consents row, the study row and the Global row; nothing else"
        );
        assertEquals(1, counts.noConsentStudyRows());
        assertEquals(0, counts.unroutableRows());
        assertTrue(counts.balanced());
        assertEquals(3, RoutedInvariant.assertHolds(tempDir.resolve("data/small.csv"), tempDir.resolve("small-routed")));
        assertEquals(
            paths.size(), reasons(tempDir.resolve("small-routed-failures.jsonl")).stream().filter("CONSENT_ROW_REJECTED"::equals).count()
        );
    }

    @Test
    void aNonCanonicalRowNeverGrantsEvenWithAValidConsentFieldsAndEvenAlone() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\ ,,phs000000.c1,0", "1,\\_consents\\phs000000.c1\\,,phs000000.c1,0", "1,\\Global\\G\\,1,,",
            "1,\\phs000000\\A\\,1,,"
        );

        assertEquals(0, counts.patientsWithConsents());
        assertEquals(Map.of(), counts.rowsPerPartition());
        assertEquals(1, counts.noConsentGlobalRows());
        assertEquals(1, counts.noConsentStudyRows());
        assertTrue(counts.balanced());
        ConsentRouter router = new ConsentRouter(tempDir.resolve("data/small.csv"), "t");
        router.learnConsents();
        assertEquals(List.of(), router.partitionNames());
    }

    @Test
    void aCanonicalRowKeepsGrantingInBothSpellingsAndAnUntrimmedConsentIsTrimmed() throws Exception {
        ConsentRouter.Counts counts =
            route("1,\\_consents\\,,  phs000000.c1 ,0", "2,µ_consentsµ,,phs000000.c1,0", "2,\\phs000000\\A\\,1,,");

        assertEquals(Map.of("phs000000.c1", 3L), counts.rowsPerPartition());
        assertEquals(0, counts.consentRowsRejected());
    }

    // ---- vectors and the invariant --------------------------------------------------------------------------------------------

    @Test
    void theV3VectorFilesMatchTheirChecksumsAndUseTestAccessionsOnly() throws Exception {
        List<String> lines = resource(V3, "SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(4, lines.size());
        for (String line : lines) {
            String[] parts = line.split("  ", 2);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(V3, parts[1]).getBytes(StandardCharsets.UTF_8));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
        String text = resource(V3, INPUT) + resource(V3, UNPARSABLE) + resource(V3, EXPECTED);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("phs[0-9]{6}").matcher(text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        assertEquals(Set.of("phs000000", "phs999999", "phs123456"), found);
    }

    @Test
    void theV3VectorRoutesExactlyAsTheIndependentScriptDerivedIt() throws Exception {
        Path input = writeInput("v3.csv", resource(V3, INPUT));
        Path routed = tempDir.resolve("routed-v3");

        ConsentRouter.Counts counts = route(input, routed);

        Map<String, List<String>> expected = new TreeMap<>();
        for (String line : resource(V3, EXPECTED).lines().toList()) {
            CSVRecord r = CSVFormat.DEFAULT.parse(new StringReader(line)).getRecords().get(0);
            List<String> fields = new ArrayList<>();
            r.forEach(fields::add);
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
        JsonNode want = JSON.readTree(resource(V3, COUNTS));
        assertEquals(want.get("rowsRead").asLong(), counts.rowsRead());
        assertEquals(want.get("rowsRouted").asLong(), counts.rowsRouted());
        assertEquals(want.get("studyRows").asLong(), counts.studyRows());
        assertEquals(want.get("globalRows").asLong(), counts.globalRows());
        assertEquals(want.get("noConsentStudyRows").asLong(), counts.noConsentStudyRows());
        assertEquals(want.get("noConsentGlobalRows").asLong(), counts.noConsentGlobalRows());
        assertEquals(want.get("unroutableRows").asLong(), counts.unroutableRows());
        assertEquals(want.get("consentRowsRejected").asLong(), counts.consentRowsRejected());
        assertEquals(want.get("linesWritten").asLong(), counts.linesWritten());
        assertEquals(want.get("patientsWithConsents").asLong(), counts.patientsWithConsents());
        Map<String, Long> byReason = new TreeMap<>();
        want.get("consentRowsRejectedByReason").fields().forEachRemaining(e -> byReason.put(e.getKey(), e.getValue().asLong()));
        assertEquals(byReason, counts.consentRowsRejectedByReason());
        List<String> only = new ArrayList<>();
        want.get("studiesOnlyInRejectedConsentRows").forEach(n -> only.add(n.asText()));
        assertEquals(only, counts.studiesOnlyInRejectedConsentRows());
        assertEquals(List.of(), counts.studyNodesWithoutConsents());
        assertTrue(counts.balanced());
        assertEquals(want.get("linesWritten").asLong(), RoutedInvariant.assertHolds(input, routed));
    }

    @Test
    void theInvariantHoldsOnTheRoutedOutputOfEveryVector() throws Exception {
        for (String dir : List.of(V1, V2, V3)) {
            Path input = writeInput("vector" + dir.replace('/', '-') + ".csv", resource(dir, INPUT));
            Path routed = tempDir.resolve("routed" + dir.replace('/', '-'));
            ConsentRouter.Counts counts;
            if (dir.equals(V2)) { // v2 has one unparsable consents line, which refuses the run: route the rest
                String text = resource(dir, INPUT);
                String line = "15,\"\\_consents\\,,phs000000.c1,0\n";
                assertTrue(text.contains(line));
                input = writeInput("v2-parsable.csv", text.replace(line, ""));
            }
            counts = route(input, routed);

            assertEquals(counts.linesWritten(), RoutedInvariant.assertHolds(input, routed), dir);
            assertTrue(counts.linesWritten() > 0, dir);
        }
    }

    @Test
    void v3RunsRefusedWithDefaultLimitsAndLoadsWithRaisedOnesWithoutLeakingAStudy() throws Exception {
        Path input = writeInput("v3.csv", resource(V3, INPUT));

        Path refused = newBase("v3-default");
        assertEquals(1, run(refused, props(input)));
        assertEquals("failed", summary(refused).get("status").asText());
        String error = summary(refused).get("error").asText();
        assertTrue(error.contains("11 row(s) of the consents variable may not grant a consent"), error);
        assertTrue(error.contains("NON_CANONICAL_PATH=8"), error);
        assertTrue(error.contains("maxRejectedConsentRows"), error);
        assertEquals(Set.of(), subdirectories(refused), "nothing was loaded");

        Path base = newBase("v3-raised");
        assertEquals(0, run(base, props(input, "picsure.loader.maxRejectedConsentRows", "11", "picsure.loader.keepRouted", "true")));

        JsonNode routing = summary(base).get("routing");
        assertEquals("ok", summary(base).get("status").asText());
        assertEquals(11, routing.get("consentRowsRejected").asLong());
        assertEquals(8, routing.get("consentRowsRejectedByReason").get("NON_CANONICAL_PATH").asLong());
        assertEquals(11, routing.get("noConsentStudyRows").asLong(), "the phs999999 row of patient 1 is counted as NO_CONSENT");
        assertEquals(0, routing.get("studyNodesWithoutConsents").size());
        assertEquals("phs123456", routing.get("studiesOnlyInRejectedConsentRows").get(0).asText());
        assertEquals("phs999999", routing.get("studiesOnlyInRejectedConsentRows").get(1).asText());
        assertEquals(Set.of("phs000000.c1", "tutorial-biolincc_test"), subdirectories(base));
        Path routed = CombinedInputTest.spoolOf(base).resolve(".routed");
        assertEquals(22, RoutedInvariant.assertHolds(input, routed));
        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(base.toString(), true);
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), store.getPatientIds(Set.of("phs000000.c1")));
        assertEquals(Set.of(1), store.getPatientIds(Set.of("tutorial-biolincc_test")));
        for (String line : Files.readAllLines(routed.resolve("phs000000.c1-allConcepts.csv"))) {
            assertFalse(line.contains("phs999999") && line.contains("\\X\\"), line);
            assertFalse(line.contains("phs123456\\"), line);
        }
        // the dropped rows are in the routing failures, as NO_CONSENT
        List<String> reasons = reasons(base.resolve("routing-failures.jsonl"));
        assertEquals(12, reasons.stream().filter("NO_CONSENT"::equals).count(), "11 study rows and the global row of patient 12");
        assertEquals(11, reasons.stream().filter("CONSENT_ROW_REJECTED"::equals).count());
    }

    @Test
    void v3WithItsUnparsableConsentsLineIsRefusedAtAnyLimit() throws Exception {
        Path input = writeInput(
            "v3-unparsable.csv", resource(V3, INPUT) + resource(V3, UNPARSABLE).lines().skip(1).collect(Collectors.joining("\n")) + "\n"
        );
        Path base = newBase("v3-unparsable");

        assertEquals(2, run(base, props(input, "picsure.loader.maxRejectedConsentRows", "1000", "picsure.loader.maxRejectedRows", "1000")));
        assertEquals("refused", summary(base).get("status").asText());
    }

    @Test
    void noRowOfAStudyEverLandsWhereItsPatientHoldsNoValidConsentOfIt() throws Exception {
        String[] studies = {"phs000000", "phs999999", "phs123456", "tutorial-biolincc_test"};
        String[] consentPaths = {"\\_consents\\", "\\_consents\\", "µ_consentsµ", "\\_consents\\ ", "\\_consents", "\\_consents\\\\",
            "  \\_consents\\", "\\_cons\uFFFDents\\", "µ_consents\\", "\\ _consents \\"};
        String[] nvals = {"", "", "", "", "5", "x"};
        String[] timestamps = {"0", "", "", "2023-06-06", "soon"};
        Random random = new Random(2026);
        int checked = 0;
        int refusedRounds = 0;
        int malformedShapes = 0;
        for (int round = 0; round < 1000; round++) {
            // one spelling of the case per study in the consents of a round (two valid consents of one study in two cases are refused by the
            // loader and would share a file on a case-insensitive file system); every tenth round lets the case run free
            boolean freeCase = round % 10 == 0;
            Boolean[] upperCase = new Boolean[studies.length];
            for (int i = 0; i < upperCase.length; i++) {
                upperCase[i] = freeCase ? null : random.nextBoolean();
            }
            List<String> lines = new ArrayList<>();
            lines.add("patient_num,concept_path,nval,tval,timestamp");
            for (int i = 0, n = 6 + random.nextInt(40); i < n; i++) {
                int patient = 1 + random.nextInt(6);
                if (random.nextInt(3) == 0) {
                    int which = random.nextInt(studies.length);
                    String study = respell(studies[which], random, upperCase[which]);
                    String tval = study.toLowerCase().startsWith("phs") ? study + ".c" + random.nextInt(3) : study;
                    String path = consentPaths[random.nextInt(consentPaths.length)];
                    if (random.nextInt(6) == 0) {
                        path = "\\_consents\\" + tval + "\\"; // the consent as a last segment
                    }
                    if (random.nextInt(8) == 0) {
                        tval = "  " + tval + " ";
                    }
                    if (random.nextInt(12) == 0) {
                        tval = study + ".bad/name";
                    }
                    String row = patient + "," + path + "," + nvals[random.nextInt(nvals.length)] + "," + tval + ","
                        + timestamps[random.nextInt(timestamps.length)];
                    if (random.nextInt(15) == 0) {
                        row += ",extra";
                    }
                    lines.add(row);
                } else {
                    String node = random.nextInt(8) == 0 ? "Global" : respell(studies[random.nextInt(studies.length)], random, null);
                    if (random.nextInt(10) == 0) {
                        node = " " + node;
                    }
                    // round 5: about one path in ten has no leading delimiter or an empty first node
                    String[] prefixes = {"\\", "\\", "\\", "\\", "\\", "\\", "\\", "", "\\\\", "  "};
                    String prefix = prefixes[random.nextInt(prefixes.length)];
                    malformedShapes += prefix.equals("\\") ? 0 : 1;
                    String row = patient + "," + prefix + node + "\\Concept" + random.nextInt(3) + "\\," + random.nextInt(100) + ",,";
                    lines.add(random.nextInt(20) == 0 ? row.replace("\\Concept", "\"\\Concept") : row);
                }
            }
            Path input = writeInput("fuzz.csv", String.join("\n", lines) + "\n");
            Path routed = tempDir.resolve("fuzz-routed-" + round);
            if (freeCase) {
                ConsentRouter probe = new ConsentRouter(input, "probe");
                probe.learnConsents();
                if (!ConsentRouter.caseClashes(probe.partitionNames()).isEmpty() || !probe.studyFoldClashes().isEmpty()) {
                    refusedRounds++; // PICSURELoader refuses these before any work (see ConsentFoldTest)
                    continue;
                }
            }

            ConsentRouter.Counts counts = route(input, routed);

            assertTrue(counts.balanced(), "round " + round);
            try {
                checked += RoutedInvariant.assertHolds(input, routed);
            } catch (AssertionError e) {
                throw new AssertionError("round " + round + ": " + e.getMessage() + "\n" + Files.readString(input).replace("\uFFFD", "<FFFD>"), e);
            }
        }
        assertTrue(checked > 500, "the fuzz must route something: " + checked);
        assertTrue(malformedShapes > 100, "the fuzz must write paths with no leading delimiter or an empty first node: " + malformedShapes);
        assertTrue(refusedRounds > 0 && refusedRounds < 100, "free-case rounds that clash are refused: " + refusedRounds);
    }

    /**
     * The study with U+FFFD, an inner or a leading/trailing blank, or upper case inside it, about every second time (round 4: the spellings
     * of a study that the loader or a reader could take for the same one; round 5: also a supplementary format character).
     */
    static String respell(String study, Random random, Boolean upperCase) {
        String s = study;
        if (upperCase == null ? random.nextInt(4) == 0 : upperCase) {
            s = s.toUpperCase();
        }
        if (random.nextInt(6) == 0) {
            int at = random.nextInt(s.length() + 1);
            s = s.substring(0, at) + "\uFFFD" + s.substring(at);
        }
        if (random.nextInt(6) == 0) {
            int at = 1 + random.nextInt(Math.max(1, s.length() - 1));
            s = s.substring(0, at) + " " + s.substring(at);
        }
        if (random.nextInt(8) == 0) {
            // a supplementary format character (tag characters U+E0001-E007F are category Cf); after the BMP inserts, so no pair is split
            int at = random.nextInt(s.length() + 1);
            s = s.substring(0, at) + new String(Character.toChars(0xE0020 + random.nextInt(8))) + s.substring(at);
        }
        if (random.nextInt(10) == 0) {
            s = " " + s;
        }
        return s;
    }

    // ---- fix 3: the spool directory -------------------------------------------------------------------------------------------

    @Test
    void aSpoolDirectoryThatContainsTheBaseIsRefusedBeforeAnyWork() throws Exception {
        Path base = newBase("spool-contains");
        Path input = writeInput("v1.csv", resource(V1, INPUT));
        Path parent = base.getParent();
        Path link = tempDir.resolve("link-to-parent");
        Files.createSymbolicLink(link, parent);

        for (
            Path spool : List
                .of(parent, tempDir, base.resolve("..").normalize(), base.resolve("../.."), link, tempDir.resolve("bases/../bases"))
        ) {
            assertEquals(
                2, run(base, props(input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.spoolDir", spool.toString())), "" + spool
            );
            JsonNode summary = summary(base);
            assertEquals("refused", summary.get("status").asText());
            assertTrue(summary.get("error").asText().contains("contains the base"), summary.get("error").asText());
            assertEquals(Set.of(), subdirectories(base), "nothing was written into the base");
            assertFalse(Files.exists(parent.resolve(".routed")), "nothing was routed into the ancestor");
            assertFalse(Files.exists(spool.resolve(".routed")));
        }
        // the base itself and a directory inside it are refused as before
        assertEquals(2, run(base, props(input, "picsure.loader.spoolDir", base.toString())));
        assertTrue(summary(base).get("error").asText().contains("inside the base"), summary(base).get("error").asText());
        assertEquals(2, run(base, props(input, "picsure.loader.spoolDir", base.resolve("in").toString())));
        // a sibling is fine, also one that shares the prefix of the base name
        assertEquals(0, run(base, props(input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.spoolDir", base + "-spool")));
    }

    // ---- fix 5: the hook is injected, not static ------------------------------------------------------------------------------

    @Test
    void theSpoolWriteHookBelongsToItsRouterAndNothingElse() throws Exception {
        Path input = writeInput("v1.csv", resource(V1, INPUT));
        AtomicLong seen = new AtomicLong();
        LongConsumer hook = n -> seen.set(n);

        ConsentRouter hooked = new ConsentRouter(input, "a", 128, hook);
        hooked.learnConsents();
        ConsentRouter plain = new ConsentRouter(input, "b");
        plain.learnConsents();
        long expected;
        try (FailureSink sink = new FailureSink(tempDir.resolve("plain.jsonl"))) {
            expected = plain.route(tempDir.resolve("plain-routed"), sink).linesWritten();
        }
        assertEquals(0, seen.get(), "another router's run does not call this hook");
        try (FailureSink sink = new FailureSink(tempDir.resolve("hooked.jsonl"))) {
            hooked.route(tempDir.resolve("hooked-routed"), sink);
        }

        assertEquals(expected, seen.get(), "called once per spool line, with the running count");
        assertTrue(
            Stream.of(ConsentRouter.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("spoolWriteHook") && java.lang.reflect.Modifier.isStatic(f.getModifiers()))
        );
    }
}
