package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer.StoreFixture;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review round 2 of the combined input: the validity gate of {@code _consents} rows, the study-node check, the spool and routed files, the
 * input pin, stale partitions, the open-writer cap. The vectors in {@code consent-vectors/v2} were checked against the real splitter (see
 * their README); test accessions only: phs000000, phs999999, phs123456.
 */
class ConsentGateTest {

    private static final String KEY = "1111111111111111";
    private static final String V2 = "/consent-vectors/v2/";
    private static final String V1 = "/consent-vectors/v1/";
    private static final String INPUT = "combined-allConcepts-input.csv";
    private static final String EXPECTED = "expected-partitions.csv";
    private static final String COUNTS = "expected-counts.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static String resource(String dir, String name) throws IOException {
        try (InputStream in = ConsentGateTest.class.getResourceAsStream(dir + name)) {
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

    private Path v2Input() throws IOException {
        return writeInput("v2.csv", resource(V2, INPUT));
    }

    /** v2 without its one unparsable consents line (which refuses a run whatever the limits): the rest loads with raised limits. */
    private Path v2InputWithoutTheUnparsableConsentsLine() throws IOException {
        String text = resource(V2, INPUT);
        String line = "15,\"\\_consents\\,,phs000000.c1,0\n";
        assertTrue(text.contains(line));
        return writeInput("v2-parsable.csv", text.replace(line, ""));
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

    private static int run(Path base, Map<String, String> properties, LongConsumer spoolWriteHook) {
        return PICSURELoader.run(new String[] {base.toString()}, properties::get, spoolWriteHook);
    }

    private static JsonNode summary(Path base) throws IOException {
        return JSON.readTree(Files.readString(base.resolve("ingest-summary.json")));
    }

    private static Path spoolOf(Path base) {
        return CombinedInputTest.spoolOf(base);
    }

    private static Set<String> subdirectories(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).map(d -> d.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static List<String> record(String line) {
        List<String> fields = ConsentRouter.parseLine(line);
        assertNotNull(fields, line);
        return fields;
    }

    private ConsentRouter.Counts route(String... lines) throws IOException {
        Path input = writeInput("small.csv", String.join("\n", lines) + "\n");
        ConsentRouter router = new ConsentRouter(input, "test");
        router.learnConsents();
        try (FailureSink sink = new FailureSink(tempDir.resolve("small-failures.jsonl"))) {
            return router.route(tempDir.resolve("small-routed"), sink);
        }
    }

    private static List<String> reasons(Path jsonl) throws IOException {
        List<String> reasons = new ArrayList<>();
        for (String line : Files.readAllLines(jsonl)) {
            reasons.add(JSON.readTree(line).get("reasonCode").asText());
        }
        return reasons;
    }

    // ---- 1. the gate ----------------------------------------------------------------------------------------------------

    @Test
    void aConsentsRowMustMeetTheSplittersContractToGrant() throws Exception {
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,0")));
        assertNull(ConsentRowCheck.check(record("-7,\\_consents\\,,phs000000.c1,")));
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,1700000000000")));
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,2023-06-06T15:20:00Z")), "ISO instant");
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,2023-06-06T15:20:00+02:00")), "ISO with an offset");
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,2021-01-15 10:30:00.5")), "space separated");
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,2025-02-28T12:56:40.500")), "ISO local date-time");
        assertNull(ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,2023-06-06")), "ISO date");

        assertEquals(ConsentRowCheck.Problem.BAD_FIELD_COUNT, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1")), "4 fields");
        assertEquals(
            ConsentRowCheck.Problem.BAD_FIELD_COUNT, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,0,x")), "6 fields"
        );
        assertEquals(ConsentRowCheck.Problem.BAD_FIELD_COUNT, ConsentRowCheck.check(record("1,\\_consents\\,phs000000.c1")), "3 fields");
        assertEquals(ConsentRowCheck.Problem.BAD_PATIENT_NUM, ConsentRowCheck.check(record("\" 5\",\\_consents\\,,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.BAD_PATIENT_NUM, ConsentRowCheck.check(record("\"5 \",\\_consents\\,,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.BAD_PATIENT_NUM, ConsentRowCheck.check(record(",\\_consents\\,,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.BAD_PATIENT_NUM, ConsentRowCheck.check(record("abc,\\_consents\\,,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.BAD_PATIENT_NUM, ConsentRowCheck.check(record("99999999999,\\_consents\\,,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.BAD_NVAL, ConsentRowCheck.check(record("1,\\_consents\\,garbage,phs000000.c1,0")));
        assertEquals(
            ConsentRowCheck.Problem.BAD_NVAL, ConsentRowCheck.check(record("1,\\_consents\\,\" \",phs000000.c1,0")), "blank is not empty"
        );
        assertEquals(ConsentRowCheck.Problem.NUMERIC_NVAL, ConsentRowCheck.check(record("1,\\_consents\\,1,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.NUMERIC_NVAL, ConsentRowCheck.check(record("1,\\_consents\\,NaN,phs000000.c1,0")));
        assertEquals(ConsentRowCheck.Problem.BAD_TIMESTAMP, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,notatime")));
        assertEquals(ConsentRowCheck.Problem.BAD_TIMESTAMP, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,2023-13-45")));
        assertEquals(
            ConsentRowCheck.Problem.BAD_TIMESTAMP, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,99999999999999999999"))
        );
        assertEquals(ConsentRowCheck.Problem.BAD_TIMESTAMP, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,-")));
        assertEquals(
            ConsentRowCheck.Problem.BAD_TIMESTAMP, ConsentRowCheck.check(record("1,\\_consents\\,,phs000000.c1,\" 5\"")), "untrimmed"
        );
    }

    @Test
    void aRejectedConsentsRowGrantsNothingAndIsWrittenNowhere() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,garbage,phs000000.c1,0", "2,\\_consents\\,7,phs000000.c1,0", "3,\\_consents\\,,phs000000.c1",
            "4,\\_consents\\,,phs000000.c1,0,x", "\" 5\",\\_consents\\,,phs000000.c1,0", "6,\\_consents\\,,phs000000.c1,soon",
            "7,\\_consents\\,,phs000000.c2,0", "1,\\phs000000\\A\\,1,,", "2,\\phs000000\\A\\,1,,", "5,\\phs000000\\A\\,1,,",
            "6,\\phs000000\\A\\,1,,", "7,\\phs000000\\A\\,1,,", "1,\\Global\\A\\,1,,"
        );

        assertEquals(6, counts.consentRowsRejected());
        assertEquals(
            Map.of("BAD_NVAL", 1L, "NUMERIC_NVAL", 1L, "BAD_FIELD_COUNT", 2L, "BAD_PATIENT_NUM", 1L, "BAD_TIMESTAMP", 1L),
            counts.consentRowsRejectedByReason()
        );
        assertEquals(1, counts.patientsWithConsents(), "only patient 7 has a consent");
        assertEquals(Map.of("phs000000.c2", 2L), counts.rowsPerPartition(), "patient 7: its consents row and its study row, nothing else");
        assertEquals(4, counts.noConsentStudyRows(), "the study rows of the four patients (1, 2, 5, 6) whose consents rows were rejected");
        assertEquals(1, counts.noConsentGlobalRows());
        assertEquals(0, counts.unroutableRows(), "a rejected consents row is not one of the unroutable rows");
        assertTrue(counts.balanced());
        assertEquals(6, reasons(tempDir.resolve("small-failures.jsonl")).stream().filter("CONSENT_ROW_REJECTED"::equals).count());
    }

    @Test
    void aConsentsRowAlsoCountsWhenItHasFewerThanFourFields() throws Exception {
        ConsentRouter.Counts counts = route("1,\\_consents\\,phs000000.c1", "2,\\_consents\\,,phs000000.c1,", "2,\\phs000000\\A\\,1,,");

        assertEquals(Map.of("BAD_FIELD_COUNT", 1L), counts.consentRowsRejectedByReason(), "3 fields: rejected, not a MALFORMED_RECORD");
        assertEquals(0, counts.unroutableRows());
        assertEquals(Map.of("phs000000.c1", 2L), counts.rowsPerPartition());
    }

    @Test
    void theV2VectorFilesMatchTheirChecksumsAndV1StillDoes() throws Exception {
        for (String dir : List.of(V1, V2)) {
            List<String> lines = resource(dir, "SHA256SUMS").lines().filter(l -> !l.isBlank()).toList();
            assertEquals(3, lines.size(), dir);
            for (String line : lines) {
                String[] parts = line.split("  ", 2);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(resource(dir, parts[1]).getBytes(StandardCharsets.UTF_8));
                assertEquals(parts[0], HexFormat.of().formatHex(digest), dir + parts[1]);
            }
        }
        String text = resource(V2, INPUT) + resource(V2, EXPECTED);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("phs[0-9]{6}").matcher(text);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        assertEquals(Set.of("phs000000", "phs999999", "phs123456"), found);
    }

    @Test
    void theV2VectorRoutesExactlyAsExpected() throws Exception {
        Path routed = tempDir.resolve("routed-v2");
        ConsentRouter router = new ConsentRouter(v2Input(), "test");
        router.learnConsents();
        ConsentRouter.Counts counts;
        try (FailureSink sink = new FailureSink(tempDir.resolve("v2-failures.jsonl"))) {
            counts = router.route(routed, sink);
        }

        Map<String, List<String>> expected = new TreeMap<>();
        for (String line : resource(V2, EXPECTED).lines().toList()) {
            List<CSVRecord> parsed = CSVFormat.DEFAULT.parse(new StringReader(line)).getRecords();
            CSVRecord r = parsed.get(0);
            List<String> fields = new ArrayList<>();
            r.forEach(fields::add);
            expected.computeIfAbsent(fields.get(0), k -> new ArrayList<>()).add(String.join("|", fields.subList(1, fields.size())));
        }
        assertEquals(expected.keySet(), counts.rowsPerPartition().keySet());
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            List<String> actual = new ArrayList<>();
            for (String line : Files.readAllLines(routed.resolve(entry.getKey() + "-allConcepts.csv"))) {
                List<String> fields = new ArrayList<>();
                record(line).forEach(fields::add);
                actual.add(String.join("|", fields));
            }
            assertEquals(entry.getValue().stream().sorted().toList(), actual.stream().sorted().toList(), entry.getKey());
        }
        JsonNode want = JSON.readTree(resource(V2, COUNTS));
        assertEquals(want.get("rowsRead").asLong(), counts.rowsRead());
        assertEquals(want.get("rowsRouted").asLong(), counts.rowsRouted());
        assertEquals(want.get("noConsentStudyRows").asLong(), counts.noConsentStudyRows());
        assertEquals(want.get("noConsentGlobalRows").asLong(), counts.noConsentGlobalRows());
        assertEquals(want.get("unroutableRows").asLong(), counts.unroutableRows());
        assertEquals(want.get("invalidStudyNodeRows").asLong(), counts.unroutableByReason().get("INVALID_STUDY_NODE"));
        assertEquals(want.get("consentRowsRejected").asLong(), counts.consentRowsRejected());
        assertEquals(want.get("patientsWithConsents").asLong(), counts.patientsWithConsents());
        Map<String, Long> byReason = new TreeMap<>();
        want.get("consentRowsRejectedByReason").fields().forEachRemaining(e -> byReason.put(e.getKey(), e.getValue().asLong()));
        assertEquals(byReason, counts.consentRowsRejectedByReason());
        assertTrue(counts.balanced());
    }

    @Test
    void aConsentsRowOnAnUnparsableLineRefusesTheRunWhateverTheLimits() throws Exception {
        Path base = newBase("v2-unparsable");

        int rc = run(base, props(v2Input(), "picsure.loader.maxRejectedRows", "1000", "picsure.loader.maxRejectedConsentRows", "1000"));

        assertEquals(2, rc);
        JsonNode summary = summary(base);
        assertEquals("refused", summary.get("status").asText());
        assertTrue(summary.get("error").asText().contains("cannot be parsed as CSV"), summary.get("error").asText());
        assertTrue(
            summary.get("error").asText().contains("their study is unknown") || summary.get("error").asText().contains("study is unknown")
        );
        assertEquals(0, summary.get("totals").get("partitionsOk").asLong());
        assertEquals(Set.of(), subdirectories(base), "refused before any work");
        assertFalse(Files.exists(spoolOf(base)));
        assertFalse(Files.exists(base.resolve("routing-failures.jsonl")), "refused before the routing started");
    }

    @Test
    void theV2RunIsRefusedByDefaultBeforeAnyPartitionLoads() throws Exception {
        Path base = newBase("v2-default");

        int rc = run(base, props(v2InputWithoutTheUnparsableConsentsLine()));

        assertEquals(1, rc);
        JsonNode summary = summary(base);
        assertEquals("failed", summary.get("status").asText());
        assertTrue(summary.get("error").asText().contains("may not grant a consent"), summary.get("error").asText());
        assertTrue(summary.get("error").asText().contains("maxRejectedConsentRows"), summary.get("error").asText());
        assertEquals(7, summary.get("routing").get("consentRowsRejected").asLong());
        assertEquals(2, summary.get("routing").get("consentRowsRejectedByReason").get("NUMERIC_NVAL").asLong());
        assertEquals(2, summary.get("routing").get("unroutableByReason").get("INVALID_STUDY_NODE").asLong());
        assertEquals(0, summary.get("totals").get("partitionsOk").asLong());
        assertEquals(Set.of(), subdirectories(base), "no partition was written, and no spool in the base");
        assertFalse(Files.exists(spoolOf(base)), "the routed files are gone");
        List<String> reasons = reasons(base.resolve("routing-failures.jsonl"));
        assertEquals(7, reasons.stream().filter("CONSENT_ROW_REJECTED"::equals).count());
        assertEquals(2, reasons.stream().filter("INVALID_STUDY_NODE"::equals).count());
    }

    @Test
    void theTwoLimitsAreIndependent() throws Exception {
        Path input = v2InputWithoutTheUnparsableConsentsLine();

        // enough for the 8 unroutable rows, none for the 7 rejected consents rows
        Path onlyRows = newBase("limit-rows-only");
        assertEquals(1, run(onlyRows, props(input, "picsure.loader.maxRejectedRows", "1000")));
        assertTrue(summary(onlyRows).get("error").asText().contains("maxRejectedConsentRows"), summary(onlyRows).get("error").asText());

        // enough for the consents rows, none for the unroutable rows
        Path onlyConsents = newBase("limit-consents-only");
        assertEquals(1, run(onlyConsents, props(input, "picsure.loader.maxRejectedConsentRows", "1000")));
        assertTrue(summary(onlyConsents).get("error").asText().contains("maxRejectedRows"), summary(onlyConsents).get("error").asText());
        assertFalse(summary(onlyConsents).get("error").asText().contains("maxRejectedConsentRows"));

        // exactly the numbers of v2: 8 unroutable rows, 7 consents rows
        Path exact = newBase("limit-exact");
        assertEquals(0, run(exact, props(input, "picsure.loader.maxRejectedRows", "8", "picsure.loader.maxRejectedConsentRows", "7")));
        Path oneShort = newBase("limit-one-short");
        assertEquals(1, run(oneShort, props(input, "picsure.loader.maxRejectedRows", "8", "picsure.loader.maxRejectedConsentRows", "6")));
        assertEquals(6, summary(oneShort).get("settings").get("maxRejectedConsentRows").asLong());
    }

    @Test
    void theV2RunLoadsWithAnExplicitLimitAndTheRejectedRowsOpenNothing() throws Exception {
        Path base = newBase("v2-limit");

        // 7 consents rows and 8 unroutable rows (3 of them in v1); the 2402L number is judged per partition
        int rc = run(
            base,
            props(
                v2InputWithoutTheUnparsableConsentsLine(), "picsure.loader.maxRejectedRows", "8", "picsure.loader.maxRejectedConsentRows",
                "7"
            )
        );

        assertEquals(0, rc);
        assertEquals(Set.of("phs000000.c1", "phs000000.c2", "phs123456.c1", "tutorial-biolincc_test"), subdirectories(base));
        PartitionedPhenotypicObservationStore store = new PartitionedPhenotypicObservationStore(base.toString(), true);
        assertEquals(Set.of(1, 2, 14), store.getPatientIds(Set.of("phs000000.c1")), "patient 14 keeps the consent of its good row only");
        assertEquals(Set.of(2, 7), store.getPatientIds(Set.of("phs000000.c2")));
        assertEquals(Set.of(4), store.getPatientIds(Set.of("phs123456.c1")), "patient 14's numeric-nval consent grants nothing");
        assertEquals(Set.of(1, 3), store.getPatientIds(Set.of("tutorial-biolincc_test")));
        for (int patient : List.of(8, 9, 10, 11, 12, 13, 15)) {
            assertFalse(
                store.getPatientIds(Set.of("phs000000.c1", "phs000000.c2", "phs123456.c1", "tutorial-biolincc_test")).contains(patient),
                "patient " + patient + " has no consent"
            );
        }
        JsonNode routing = summary(base).get("routing");
        assertEquals("ok", summary(base).get("status").asText());
        assertEquals(7, routing.get("consentRowsRejected").asLong());
        assertEquals(2, routing.get("unroutableByReason").get("INVALID_STUDY_NODE").asLong());
        assertEquals(4, routing.get("unroutableByReason").get("MALFORMED_RECORD").asLong(), "v1's 3 fields, a bad quote, 4 and 6 fields");
        assertEquals(2, routing.get("unroutableByReason").get("INVALID_PATIENT_ID").asLong());
        assertNull(routing.get("consentRowsRejectedByReason").get("UNPARSABLE_LINE"));
        assertTrue(routing.get("balanced").asBoolean());
        JsonNode want = JSON.readTree(resource(V2, COUNTS));
        assertEquals(want.get("rowsPerPartition"), routing.get("rowsPerPartition"));
        assertEquals(want.get("linesWritten"), routing.get("linesWritten"));
        com.fasterxml.jackson.databind.node.ObjectNode wantReasons = want.get("consentRowsRejectedByReason").deepCopy();
        wantReasons.remove("UNPARSABLE_LINE");
        assertEquals(wantReasons, routing.get("consentRowsRejectedByReason"));
    }

    // ---- 2. lines that cannot be parsed ---------------------------------------------------------------------------------

    @Test
    void anUnterminatedQuoteCostsThatLineAndNothingElse() throws Exception {
        Path base = newBase("unterminated");
        // two leading blank lines and a header, so that the physical line numbers differ from the record numbers
        Path input = writeInput(
            "bad-quote.csv",
            "\n\npatient_num,concept_path,nval,tval,timestamp\n1,\\_consents\\,,phs000000.c1,0\n2,\\_consents\\,,phs000000.c1,0\n"
                + "1,\"\\phs000000\\A\\,5,,\n2,\\phs000000\\A\\,6,,\n1,\\phs000000\\B\\,7,,\n"
        );

        int refused = assertDoesNotThrow(() -> run(base, props(input)));
        assertEquals(1, refused, "one lost row against the default of none");
        assertEquals("failed", summary(base).get("status").asText());
        assertEquals(0, summary(base).get("totals").get("partitionsOk").asLong(), "refused before any partition loads");
        assertEquals(Set.of(), subdirectories(base));

        Path again = newBase("unterminated-allowed");
        assertEquals(0, run(again, props(input, "picsure.loader.maxRejectedRows", "1")));

        JsonNode routing = summary(again).get("routing");
        assertEquals(1, routing.get("unroutableByReason").get("MALFORMED_RECORD").asLong());
        assertEquals(1, routing.get("unroutableRows").asLong());
        assertEquals(5, routing.get("rowsRead").asLong(), "every line after the header, the bad one included");
        assertEquals(4, routing.get("rowsRouted").asLong(), "the rows after the bad line are routed");
        assertTrue(routing.get("balanced").asBoolean());
        JsonNode failure = JSON.readTree(Files.readAllLines(again.resolve("routing-failures.jsonl")).get(0));
        assertEquals("MALFORMED_RECORD", failure.get("reasonCode").asText());
        assertEquals(6, failure.get("recordNumber").asLong(), "the physical line number of the input, blank lines and header counted");
        StoreFixture.loadDefaultKey(tempDir);
        assertEquals(Set.of(1, 2), StoreFixture.readAllIds(again.resolve("phs000000.c1")));
        assertTrue(StoreFixture.readColumnMeta(again.resolve("phs000000.c1")).containsKey("\\phs000000\\B\\"), "a row after the bad line");
    }

    @Test
    void anUnparsableLineThatMentionsTheConsentsVariableGrantsNothing() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "2,\"\\_consents\\,,phs000000.c1,0", "3,µ_consentsµ,,\"phs000000.c1,0",
            "2,\\phs000000\\A\\,1,,", "3,\\phs000000\\A\\,1,,", "1,\\phs000000\\A\\,1,,", "1,\"\\phs000000\\A\\,1,,"
        );

        assertEquals(Map.of("UNPARSABLE_LINE", 2L), counts.consentRowsRejectedByReason());
        assertEquals(Map.of("MALFORMED_RECORD", 1L), counts.unroutableByReason(), "a bad line that is not a consents row");
        assertEquals(1, counts.patientsWithConsents(), "only patient 1");
        assertEquals(2, counts.noConsentStudyRows(), "patients 2 and 3 got no consent from their unparsable lines");
        assertEquals(1, counts.unroutableCrashClassRows());
        assertEquals(2, counts.consentRowsRejected());
        assertTrue(counts.balanced());
    }

    @Test
    void aDataRowIsExactlyFiveFields() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\phs000000\\A\\,1,", "1,\\phs000000\\A\\,1,,,x", "1,\\phs000000\\A\\,1,,",
            "1,\\phs000000\\A\\,1"
        );

        assertEquals(Map.of("MALFORMED_RECORD", 3L), counts.unroutableByReason());
        assertEquals(3, counts.unroutableCrashClassRows(), "a row that is not routed counts against maxRejectedRows");
        assertEquals(Map.of("phs000000.c1", 2L), counts.rowsPerPartition());
        assertTrue(counts.balanced());
    }

    @Test
    void theLineParserEqualsTheCsvParserOnLinesWithoutQuotesAndFailsClosedOnBrokenOnes() throws Exception {
        Random random = new Random(7);
        char[] alphabet = {',', ',', 'a', ' ', '\\', 'µ', '1', '\t', '\u0000', '\u00a0'};
        for (int i = 0; i < 20_000; i++) {
            StringBuilder line = new StringBuilder();
            for (int j = random.nextInt(12) + 1; j > 0; j--) {
                line.append(alphabet[random.nextInt(alphabet.length)]);
            }
            List<String> expected = new ArrayList<>();
            try (var parser = CSVFormat.DEFAULT.parse(new StringReader(line.toString()))) {
                parser.getRecords().get(0).forEach(expected::add);
            }
            assertEquals(expected, ConsentRouter.parseLine(line.toString()), "[" + line + "]");
        }
        assertEquals(List.of("a", "b,c", "d"), ConsentRouter.parseLine("a,\"b,c\",d"));
        assertEquals(List.of("a", "x\"y", ""), ConsentRouter.parseLine("a,\"x\"\"y\","));
        assertNull(ConsentRouter.parseLine("a,\"b"), "unterminated");
        assertNull(ConsentRouter.parseLine("a,\"b\"c,d"), "text after the closing quote");
    }

    // ---- 3. spool and routed files --------------------------------------------------------------------------------------

    @Test
    void theDefaultSpoolIsNextToTheBaseNeverInsideIt() throws Exception {
        Path base = newBase("spool-default");
        AtomicReference<Set<String>> baseDirsDuringRouting = new AtomicReference<>();
        AtomicReference<Boolean> routedDuringRouting = new AtomicReference<>();
        LongConsumer hook = n -> {
            if (n == 1) {
                try {
                    baseDirsDuringRouting.set(subdirectories(base));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                routedDuringRouting.set(Files.isDirectory(spoolOf(base).resolve(".routed")));
            }
        };

        assertEquals(0, run(base, props(writeInput("v1.csv", resource(V1, INPUT)), "picsure.loader.maxRejectedRows", "3"), hook));

        assertEquals(Set.of(), baseDirsDuringRouting.get(), "HPDS would read any directory in the base as a partition");
        assertTrue(routedDuringRouting.get(), "the routed files are in <base>.picsure-spool/.routed");
        assertFalse(Files.exists(spoolOf(base)), "gone afterwards");
        assertEquals(Set.of("phs000000.c1", "phs000000.c2", "phs123456.c1", "tutorial-biolincc_test"), subdirectories(base));
        assertEquals(0, summary(base).get("stalePartitions").size());
    }

    @Test
    void aSpoolDirectoryInsideTheBaseIsRefusedInCombinedMode() throws Exception {
        Path base = newBase("spool-inside");
        Path input = writeInput("v1.csv", resource(V1, INPUT));

        assertEquals(2, run(base, props(input, "picsure.loader.spoolDir", base.resolve("spool").toString())));
        assertTrue(summary(base).get("error").asText().contains("inside the base"), summary(base).get("error").asText());
        assertEquals(2, run(base, props(input, "picsure.loader.spoolDir", base.toString())));
        assertEquals(2, run(base, props(input, "picsure.loader.spoolDir", base.resolve("a/../b/c").toString())));
        assertEquals(
            2, run(base, props(input, "picsure.loader.spoolDir", base.resolve("x").toString(), "picsure.loader.partitionBy", "none"))
        );
        Path link = tempDir.resolve("link-to-base");
        Files.createSymbolicLink(link, base);
        assertEquals(2, run(base, props(input, "picsure.loader.spoolDir", link.resolve("spool").toString())), "a link into the base");

        assertEquals(Set.of(), subdirectories(base), "nothing was written");
        assertEquals(
            0, run(base, props(input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.spoolDir", tempDir.resolve("fine").toString()))
        );
    }

    @Test
    void aSplitInputKeepsItsEarlierDefaultSpoolInTheBase() throws Exception {
        Path base = newBase("split-spool");
        Files.writeString(base.resolve("p-allConcepts.csv"), "1,\\a\\,1,,\n");

        assertEquals(0, PICSURELoader.run(new String[] {base.toString()}, key -> null));
        assertEquals(Set.of("p"), subdirectories(base), "its spool is removed when the run ends");
    }

    @Test
    void keepRoutedLeavesTheRoutedFilesOutsideTheBase() throws Exception {
        Path base = newBase("keep-routed");

        assertEquals(
            0,
            run(
                base,
                props(writeInput("v1.csv", resource(V1, INPUT)), "picsure.loader.maxRejectedRows", "3", "picsure.loader.keepRouted", "true")
            )
        );

        Path routed = spoolOf(base).resolve(".routed");
        try (Stream<Path> files = Files.list(routed)) {
            assertEquals(4, files.filter(Files::isRegularFile).count(), "one routed file per partition, also after the successes");
        }
        assertEquals(routed.toRealPath(), Path.of(summary(base).get("routing").get("routedDirKept").asText()).toRealPath());
        assertFalse(subdirectories(base).stream().anyMatch(d -> d.contains("spool") || d.contains("routed")));
    }

    @Test
    void theRoutedDirectoryIsGoneAfterAPartitionFailureUnlessKept() throws Exception {
        Path input = writeInput("v1.csv", resource(V1, INPUT));
        Path gone = newBase("fail-gone");
        Path kept = newBase("fail-kept");

        assertEquals(1, run(gone, props(input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.maxObservationsPerConcept", "3")));
        assertEquals(
            1,
            run(
                kept,
                props(
                    input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.maxObservationsPerConcept", "3",
                    "picsure.loader.keepRouted", "true"
                )
            )
        );

        assertFalse(Files.exists(spoolOf(gone)), "failFast stopped the run, the unloaded routed files go too");
        assertTrue(Files.isRegularFile(spoolOf(kept).resolve(".routed/phs000000.c1-allConcepts.csv")));
        assertFalse(Files.exists(gone.resolve("phs000000.c1/allObservationsStore.javabin")));
    }

    @Test
    void theRoutedDirectoryIsGoneAfterASimulatedDiskFull() throws Exception {
        Path base = newBase("disk-full");
        Path explicit = newBase("disk-full-explicit");
        Path spool = tempDir.resolve("fast-disk");
        LongConsumer hook = n -> {
            if (n == 5) {
                throw new UncheckedIOException(new IOException("No space left on device"));
            }
        };
        Path input = writeInput("v1.csv", resource(V1, INPUT));

        assertEquals(1, run(base, props(input), hook));
        assertEquals(1, run(explicit, props(input, "picsure.loader.spoolDir", spool.toString()), hook));

        assertTrue(summary(base).get("error").asText().contains("routing failed"), summary(base).get("error").asText());
        assertEquals("failed", summary(base).get("status").asText());
        assertFalse(Files.exists(spoolOf(base)), "the half-written routed files are deleted");
        try (Stream<Path> left = Files.walk(spool)) {
            assertEquals(List.of(), left.filter(p -> !p.equals(spool)).toList(), "an explicit spool root is kept, but empty");
        }
        assertEquals(Set.of(), subdirectories(base));
    }

    // ---- 4. path normalization ------------------------------------------------------------------------------------------

    @Test
    void aStudyNodeThatChangesWhenTheLoaderNormalizesThePathIsNeverRouted() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,0", "1,\\ phs000000\\A\\,1,,", "1,\\phs000000 \\A\\,2,,", "1,\\phs000000�\\A\\,3,,",
            "1,µ phs000000µAµ,4,,", "1,\\phs000000\\A\\,5,,", "1,\\Global \\A\\,6,,", "1,\\Global\\A\\,7,,"
        );

        assertEquals(5, counts.unroutableByReason().get("INVALID_STUDY_NODE"), "also a global node: ' Global' would be 'Global'");
        assertEquals(5, counts.unroutableRows());
        assertEquals(5, counts.unroutableCrashClassRows(), "lost rows count against maxRejectedRows");
        assertEquals(
            Map.of("phs000000.c1", 3L), counts.rowsPerPartition(), "the consents row, the clean study row and the clean global row"
        );
        assertTrue(counts.balanced());
    }

    @Test
    void theFastPathNeverMissesAChangeOfTheNodeThatTheLoadersNormalizationMakes() {
        char[] alphabet = {'\\', 'µ', ' ', '�', 'a', '\t', 'b', '\u0000'};
        Random random = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            int length = random.nextInt(9);
            StringBuilder path = new StringBuilder();
            for (int j = 0; j < length; j++) {
                path.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String raw = path.toString();
            String node = ConsentRouter.segment(raw, 1);
            boolean changes = !node.equals(ConsentRouter.segment(CsvObservationProducer.normalizeConceptPath(raw), 1));
            assertEquals(changes, ConsentRouter.studyNodeChangesOnNormalization(raw, node), "path " + raw.replace("�", "<FFFD>"));
        }
    }

    // ---- 5. open writers ------------------------------------------------------------------------------------------------

    @Test
    void theOpenWriterCapIsDerivedFromTheOpenFileLimitUnlessSet() {
        LoaderConfig defaults = LoaderConfig.defaults();
        assertEquals(0, defaults.maxOpenWriters());
        assertTrue(
            defaults.effectiveMaxOpenWriters() >= 16 && defaults.effectiveMaxOpenWriters() <= 1024, "" + defaults.effectiveMaxOpenWriters()
        );
        if (
            java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.UnixOperatingSystemMXBean unix
        ) {
            long limit = unix.getMaxFileDescriptorCount();
            assertEquals(Math.max(16, Math.min(1024, limit - 256)), defaults.effectiveMaxOpenWriters());
        }
        LoaderConfig set =
            LoaderConfig.fromProperties(Map.of("picsure.loader.maxOpenWriters", "7", "picsure.loader.keepRouted", "true")::get);
        assertEquals(7, set.effectiveMaxOpenWriters());
        assertTrue(set.keepRouted());
        assertFalse(defaults.keepRouted());
        assertThrows(IllegalArgumentException.class, () -> LoaderConfig.fromProperties(Map.of("picsure.loader.maxOpenWriters", "-1")::get));
        assertThrows(IllegalArgumentException.class, () -> new ConsentRouter(tempDir.resolve("x"), "r", 0));
    }

    @Test
    void aTinyWriterCapGivesTheSameRoutedFilesAndTheSummaryNamesTheCap() throws Exception {
        Path input = writeInput("v1.csv", resource(V1, INPUT));
        Path wide = newBase("wide");
        Path narrow = newBase("narrow");

        assertEquals(0, run(wide, props(input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.keepRouted", "true")));
        assertEquals(
            0,
            run(
                narrow,
                props(
                    input, "picsure.loader.maxRejectedRows", "3", "picsure.loader.keepRouted", "true", "picsure.loader.maxOpenWriters", "2"
                )
            )
        );

        JsonNode routing = summary(narrow).get("routing");
        assertEquals(2, routing.get("maxOpenWriters").asLong());
        assertTrue(routing.get("spoolWriterEvictions").asLong() > 0, "four partitions through two writers");
        assertEquals(0, summary(wide).get("routing").get("spoolWriterEvictions").asLong());
        assertEquals(summary(wide).get("routing").get("maxOpenWriters").asLong(), LoaderConfig.defaults().effectiveMaxOpenWriters());
        for (String partition : List.of("phs000000.c1", "phs000000.c2", "phs123456.c1", "tutorial-biolincc_test")) {
            String name = partition + "-allConcepts.csv";
            assertEquals(
                Files.readString(spoolOf(wide).resolve(".routed").resolve(name)),
                Files.readString(spoolOf(narrow).resolve(".routed").resolve(name)), partition + ": same rows in the same order"
            );
        }
    }

    // ---- 6. pass 1 memory -----------------------------------------------------------------------------------------------

    @Test
    void patientsWithTheSameConsentsShareOneSetInPassOne() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int patient = 1; patient <= 5000; patient++) {
            lines.add(patient + ",\\_consents\\,,phs000000.c" + (patient % 2) + ",");
            if (patient % 3 == 0) {
                lines.add(patient + ",\\_consents\\,,phs000000.c2,");
            }
            lines.add(patient + ",\\_consents\\,,phs000000.c" + (patient % 2) + ","); // the same consent again
        }
        Path input = writeInput("many.csv", String.join("\n", lines) + "\n");
        ConsentRouter router = new ConsentRouter(input, "test");

        router.learnConsents();

        assertEquals(5000, router.patientsWithConsents());
        assertEquals(4, router.distinctConsentSets(), "{c0}, {c1}, {c0,c2}, {c1,c2}: not one set per patient");
        assertEquals(List.of("phs000000.c0", "phs000000.c1", "phs000000.c2"), router.partitionNames());
    }

    // ---- 7. input pinning -----------------------------------------------------------------------------------------------

    @Test
    void anInputThatChangesItsModificationTimeWhileRoutingIsRefused() throws Exception {
        Path base = newBase("pin-mtime");
        Path input = writeInput("v1.csv", resource(V1, INPUT));
        LongConsumer hook = n -> {
            if (n == 3) {
                try {
                    Files.setLastModifiedTime(input, FileTime.fromMillis(Files.getLastModifiedTime(input).toMillis() + 3_600_000));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };

        int rc = run(base, props(input, "picsure.loader.maxRejectedRows", "3"), hook);

        assertEquals(2, rc);
        JsonNode summary = summary(base);
        assertEquals("refused", summary.get("status").asText());
        assertTrue(summary.get("error").asText().contains("changed while it was routed"), summary.get("error").asText());
        assertEquals(Set.of(), subdirectories(base), "no partition was loaded");
        assertFalse(Files.exists(spoolOf(base)));
    }

    @Test
    void anInputThatGrowsWhileRoutingIsRefused() throws Exception {
        Path base = newBase("pin-size");
        Path input = writeInput("v1.csv", resource(V1, INPUT));
        LongConsumer hook = n -> {
            if (n == 3) {
                try {
                    Files.writeString(input, "9,\\Global\\Late\\,1,,\n", java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };

        assertEquals(2, run(base, props(input, "picsure.loader.maxRejectedRows", "3"), hook));

        assertEquals("refused", summary(base).get("status").asText());
        assertTrue(summary(base).get("error").asText().contains("changed"), summary(base).get("error").asText());
        assertEquals(Set.of(), subdirectories(base));
    }

    @Test
    void anUnchangedInputIsNotRefused() throws Exception {
        Path base = newBase("pin-ok");

        assertEquals(0, run(base, props(writeInput("v1.csv", resource(V1, INPUT)), "picsure.loader.maxRejectedRows", "3")));
    }

    // ---- 8. stale partitions --------------------------------------------------------------------------------------------

    @Test
    void directoriesInTheBaseThatThisRunDidNotProduceAreListedAndKept() throws Exception {
        Path base = newBase("stale");
        Files.createDirectories(base.resolve("old-study"));
        Files.writeString(base.resolve("old-study/allObservationsStore.javabin"), "x");
        Files.createDirectories(base.resolve(".leftover"));
        Files.createDirectories(base.resolve("phs000000.c1")); // a partition of this run: not stale
        Files.writeString(base.resolve("not-a-directory.txt"), "x");

        assertEquals(0, run(base, props(writeInput("v1.csv", resource(V1, INPUT)), "picsure.loader.maxRejectedRows", "3")));

        JsonNode stale = summary(base).get("stalePartitions");
        List<String> names = new ArrayList<>();
        stale.forEach(n -> names.add(n.asText()));
        assertEquals(List.of(".leftover", "old-study"), names);
        assertTrue(Files.isRegularFile(base.resolve("old-study/allObservationsStore.javabin")), "never deleted");
        assertTrue(Files.isDirectory(base.resolve(".leftover")));
    }

    @Test
    void theStalePartitionsAreAlsoInTheSummaryOfARefusedRun() throws Exception {
        Path base = newBase("stale-failed");
        Files.createDirectories(base.resolve("old-study"));

        assertEquals(1, run(base, props(v2InputWithoutTheUnparsableConsentsLine())));

        assertEquals("old-study", summary(base).get("stalePartitions").get(0).asText());
    }
}
