package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The routing rules in small, one at a time. Test accessions only: phs000000, phs999999, phs123456. */
class ConsentRouterTest {

    @TempDir
    Path tempDir;

    private ConsentRouter.Counts route(String... lines) throws IOException {
        Path input = tempDir.resolve("input.csv");
        Files.writeString(input, String.join("\n", lines) + "\n");
        ConsentRouter router = new ConsentRouter(input, "test");
        router.learnConsents();
        try (FailureSink sink = new FailureSink(tempDir.resolve("routing-failures.jsonl"))) {
            return router.route(tempDir.resolve("routed"), sink);
        }
    }

    @Test
    void theNodeIsTheSegmentAtDepthOneAndMuIsADelimiterToo() {
        assertEquals("phs000000", ConsentRouter.segment("\\phs000000\\Demographics\\Age\\", 1));
        assertEquals("phs000000", ConsentRouter.segment("µphs000000µLabsµ", 1));
        assertEquals("_consents", ConsentRouter.segment("\\_consents\\", 1));
        assertEquals("", ConsentRouter.segment("\\", 1));
        assertEquals("", ConsentRouter.segment("nodelimiter", 1));
        assertEquals("x", ConsentRouter.segment("a\\x\\", 1), "a path that does not start with a delimiter");
    }

    @Test
    void theStudyOfAConsentIsTheTextBeforeTheFirstDot() {
        assertEquals("phs000000", ConsentRouter.studyOf("phs000000.c1"));
        assertEquals("phs000000", ConsentRouter.studyOf("phs000000"));
        assertEquals("tutorial-biolincc_test", ConsentRouter.studyOf("tutorial-biolincc_test"));
        assertEquals("phs000000", ConsentRouter.studyOf("phs000000.c1.extra"));
    }

    @Test
    void aPatientInTwoConsentsOfOneStudyGetsTheRowInBoth() throws Exception {
        ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,", "1,\\_consents\\,,phs000000.c2,", "1,\\phs000000\\A\\,5,,");

        assertEquals(Map.of("phs000000.c1", 3L, "phs000000.c2", 3L), counts.rowsPerPartition());
        assertEquals(1, counts.studyRows());
        assertEquals(2, counts.studyRowCopies());
        assertEquals(2, counts.globalRows(), "the _consents rows are global rows");
        assertEquals(4, counts.globalRowCopies());
        assertTrue(counts.balanced());
    }

    @Test
    void aStudyRowOfAPatientWithoutThatStudyIsDroppedAndSoIsAGlobalRowOfAPatientWithoutConsents() throws Exception {
        ConsentRouter.Counts counts = route(
            "1,\\_consents\\,,phs000000.c1,", "2,\\_consents\\,,phs123456.c1,", "1,\\phs123456\\A\\,5,,", "3,\\phs123456\\A\\,5,,",
            "3,\\Global\\A\\,5,,", "1,\\Global\\A\\,5,,"
        );

        assertEquals(2, counts.noConsentStudyRows(), "patient 1 holds no phs123456 consent, patient 3 holds none at all");
        assertEquals(1, counts.noConsentGlobalRows());
        assertEquals(3, counts.noConsentRowsDropped());
        assertEquals(1, counts.patientsWithoutConsent());
        assertEquals(3, counts.globalRows(), "two _consents rows and the Global row of patient 1");
        assertEquals(Map.of("phs000000.c1", 2L, "phs123456.c1", 1L), counts.rowsPerPartition());
        assertTrue(counts.balanced());
    }

    @Test
    void aNodeThatNoConsentNamesIsAGlobalVariableAndReported() throws Exception {
        ConsentRouter.Counts counts = route("1,\\_consents\\,,phs000000.c1,", "1,\\phs999999\\M\\BMI\\,22.5,,");

        assertEquals(List.of("phs999999"), counts.studyNodesWithoutConsents());
        assertEquals(2L, counts.rowsPerPartition().get("phs000000.c1"));
        assertEquals(0, counts.studyRows());
    }

    @Test
    void aBlankConsentIsIgnoredAndAnUnparsablePatientCannotHoldConsents() throws Exception {
        ConsentRouter.Counts counts = route("1,\\_consents\\,,  ,", "x,\\_consents\\,,phs000000.c1,", "1,\\Global\\A\\,5,,");

        assertEquals(1, counts.blankConsentRows());
        assertEquals(0, counts.patientsWithConsents());
        assertEquals(1, counts.unroutableByReason().get("INVALID_PATIENT_ID"));
        assertEquals(2, counts.noConsentGlobalRows(), "the blank _consents row and the Global row of patient 1");
        assertTrue(counts.balanced());
    }

    @Test
    void manyMorePartitionsThanOpenWritersKeepEveryRowInOrder() throws Exception {
        int consents = 300;
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= consents; i++) {
            lines.add(i + ",\\_consents\\,,study" + i + ".c1,");
        }
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i <= consents; i++) {
                lines.add(i + ",\\study" + i + "\\Concept\\," + round + ",,");
            }
        }

        ConsentRouter.Counts counts = route(lines.toArray(String[]::new));

        assertEquals(consents, counts.rowsPerPartition().size());
        assertTrue(counts.rowsPerPartition().values().stream().allMatch(n -> n == 4));
        List<String> partition = Files.readAllLines(tempDir.resolve("routed/study150.c1-allConcepts.csv"));
        assertEquals(
            List.of(
                "\"150\",\"\\_consents\\\",\"\",\"study150.c1\",\"\"", "\"150\",\"\\study150\\Concept\\\",\"0\",\"\",\"\"",
                "\"150\",\"\\study150\\Concept\\\",\"1\",\"\",\"\"", "\"150\",\"\\study150\\Concept\\\",\"2\",\"\",\"\""
            ), partition
        );
    }

    @Test
    void caseClashesAreFound() {
        assertEquals(List.of("a.c1 / A.C1"), ConsentRouter.caseClashes(List.of("a.c1", "b", "A.C1")));
        assertEquals(List.of(), ConsentRouter.caseClashes(List.of("a.c1", "a.c2")));
    }
}
