package edu.harvard.hms.dbmi.avillach.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ConceptPaths} tracks which concept paths a turn has seen, so a path the model invented can be refused. */
class ConceptPathsTest {

    private static final String REAL = "\\Nhanes\\examination\\body measures\\Body Mass Index (kg per m**2)\\";

    /** A tool result as it arrives over the wire: JSON-escaped, so each backslash is doubled in the text. */
    private static final String TOOL_RESULT = "{\"total\":1,\"concepts\":[{\"conceptPath\":"
        + "\"\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\"}]}";

    @Test
    void normalizeCollapsesBackslashRunsAndEnsuresBothEnds() {
        assertEquals(REAL, ConceptPaths.normalize("\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)"));
        assertEquals(REAL, ConceptPaths.normalize("Nhanes\\examination\\body measures\\Body Mass Index (kg per m**2)"));
        assertEquals(REAL, ConceptPaths.normalize(REAL));
    }

    private static final String SLEEP_APNEA = "\\Synthea\\ACT Diagnosis ICD-10\\G00-G99 Diseases of the nervous system (G00-G99)\\"
        + "G40-G47 Episodic and paroxysmal disorders (G40-G47)\\G47 Sleep disorders\\G47.3 Sleep apnea\\";

    private static ConceptPaths learning(String... paths) {
        ConceptPaths seen = new ConceptPaths();
        for (String path : paths) {
            seen.learnFrom("{\"concepts\":[{\"conceptPath\":" + ConceptPathsTest.json(path) + "}]}");
        }
        return seen;
    }

    private static String json(String path) {
        return "\"" + path.replace("\\", "\\\\") + "\"";
    }

    @Test
    void snappedRestoresAPathWithItsCodeRangeSuffixesAndMiddleFolderDropped() {
        // v15 T14: gpt-oss wrote the Synthea path without "(G00-G99)", "(G40-G47)" and the G00-G99 folder.
        ConceptPaths paths = learning(SLEEP_APNEA);

        assertEquals(SLEEP_APNEA, paths.snapped("\\Synthea\\ACT Diagnosis ICD-10\\G40-G47 Episodic and paroxysmal disorders\\G47 Sleep disorders\\G47.3 Sleep apnea\\"));
        assertEquals(SLEEP_APNEA, paths.snapped("\\Synthea\\ACT Diagnosis ICD-10\\G47.3 Sleep apnea"));
    }

    private static final String LAB_CHOLESTEROL = "\\Nhanes\\laboratory\\biochemistry\\Cholesterol (mg per dL)\\";
    private static final String DIET_CHOLESTEROL = "\\Nhanes\\questionnaire\\food component recall\\Cholesterol (mg)\\";

    private static void assertUnchanged(ConceptPaths paths, String written) {
        assertEquals(written, paths.snapped(written));
    }

    @Test
    void snappedKeepsAWrittenParentheticalExactlyOrNotAtAll() {
        // Dropping "(mg per dL)" is a shortening; writing "(mg)" under the lab folder names a variable that does not exist.
        ConceptPaths paths = learning(LAB_CHOLESTEROL);

        assertEquals(LAB_CHOLESTEROL, paths.snapped("\\Nhanes\\laboratory\\Cholesterol\\"));
        assertEquals(LAB_CHOLESTEROL, paths.snapped("\\Nhanes\\laboratory\\Cholesterol (mg per dL)\\"));
        assertUnchanged(paths, "\\Nhanes\\laboratory\\Cholesterol (mg)\\");
        assertUnchanged(paths, "\\Nhanes\\laboratory\\Cholesterol (g per L)\\");
    }

    @Test
    void snappedRefusesWhenTwoSeenPathsDifferOnlyByTheirParenthetical() {
        ConceptPaths paths = learning(LAB_CHOLESTEROL, DIET_CHOLESTEROL);

        assertUnchanged(paths, "\\Nhanes\\Cholesterol\\");
        assertEquals(DIET_CHOLESTEROL, paths.snapped("\\Nhanes\\food component recall\\Cholesterol\\"));
        assertEquals(LAB_CHOLESTEROL, paths.snapped("\\Nhanes\\biochemistry\\Cholesterol\\"));
    }

    @Test
    void snappedNeverMovesAVariableToASiblingFolder() {
        String behavior = "\\Nhanes\\questionnaire\\smoking behavior\\Current Cigarette Smoker?\\";
        String family = "\\Nhanes\\questionnaire\\smoking family\\Does anyone smoke in home?\\";
        ConceptPaths paths = learning(behavior, family);

        assertUnchanged(paths, "\\Nhanes\\questionnaire\\smoking family\\Current Cigarette Smoker?\\");
        assertUnchanged(paths, "\\Nhanes\\questionnaire\\smoking behavior\\Does anyone smoke in home?\\");
        assertEquals(behavior, paths.snapped("\\Nhanes\\smoking behavior\\Current Cigarette Smoker?\\"));
    }

    @Test
    void snappedRefusesNearIdenticalLeavesUnderDifferentParents() {
        // Real ICD-10 shape: the same code can sit under two parents; a path that drops the parent cannot say which was meant.
        String underNervous = "\\Synthea\\ACT Diagnosis ICD-10\\G00-G99 Diseases of the nervous system (G00-G99)\\G47 Sleep disorders\\G47.3 Sleep apnea\\";
        String underSymptoms = "\\Synthea\\ACT Diagnosis ICD-10\\R00-R99 Symptoms and signs (R00-R99)\\G47 Sleep disorders\\G47.3 Sleep apnea\\";
        ConceptPaths paths = learning(underNervous, underSymptoms);

        assertUnchanged(paths, "\\Synthea\\ACT Diagnosis ICD-10\\G47 Sleep disorders\\G47.3 Sleep apnea\\");
        assertEquals(underNervous, paths.snapped("\\Synthea\\ACT Diagnosis ICD-10\\G00-G99 Diseases of the nervous system\\G47.3 Sleep apnea\\"));
        assertEquals(underSymptoms, paths.snapped("\\Synthea\\R00-R99 Symptoms and signs\\G47.3 Sleep apnea\\"));
    }

    @Test
    void snappedDoesNotSpanDatasetsOrReorderFolders() {
        ConceptPaths paths = learning(SLEEP_APNEA, "\\Nhanes\\questionnaire\\sleep disorders\\Sleep apnea\\");

        assertUnchanged(paths, "\\Nhanes\\ACT Diagnosis ICD-10\\G47.3 Sleep apnea\\");
        assertUnchanged(paths, "\\Synthea\\G47 Sleep disorders\\G40-G47 Episodic and paroxysmal disorders\\G47.3 Sleep apnea\\");
        assertUnchanged(paths, "\\Synthea\\ACT Diagnosis ICD-10\\G47.3 Sleep apnea\\G47 Sleep disorders\\");
    }

    @Test
    void snappedIsCaseAndWordingExact() {
        ConceptPaths paths = learning("\\Nhanes\\questionnaire\\supplement use\\ALCOHOL_PERCENT\\");

        assertUnchanged(paths, "\\Nhanes\\supplement use\\alcohol_percent\\");
        assertUnchanged(paths, "\\Nhanes\\supplement use\\ALCOHOL PERCENT\\");
        assertUnchanged(paths, "\\Nhanes\\supplement use\\ALCOHOL_PERCENT_X\\");
    }

    @Test
    void snappedLeavesAnAncestorFolderOfASeenPathAlone() {
        // "smoking family" is a real folder, not a variable; snapping it to a leaf under it would turn a folder into a variable.
        ConceptPaths paths = learning("\\Nhanes\\questionnaire\\smoking family\\Does anyone smoke in home?\\");

        assertUnchanged(paths, "\\Nhanes\\questionnaire\\smoking family\\");
        assertUnchanged(paths, "\\Nhanes\\questionnaire\\");
    }

    @Test
    void snappedSkipsOneFolderPathsAndBlankInput() {
        ConceptPaths paths = learning(SLEEP_APNEA);

        assertUnchanged(paths, "\\G47.3 Sleep apnea\\");
        assertUnchanged(paths, "");
        assertNull(paths.snapped(null));
    }

    @Test
    void snappedLeavesAnExactlyKnownPathAloneEvenWhenALongerOneAbbreviatesToIt() {
        String shortKnown = "\\Synthea\\ACT Diagnosis ICD-10\\G47.3 Sleep apnea\\";
        ConceptPaths paths = learning(SLEEP_APNEA, shortKnown);

        assertEquals(shortKnown, paths.snapped(shortKnown));
    }

    @Test
    void snappedLeavesAnAmbiguousUnrelatedOrFolderPathAlone() {
        ConceptPaths paths = learning(
            SLEEP_APNEA, "\\Synthea\\ACT Diagnosis ICD-10\\G00-G99 Diseases of the nervous system (G00-G99)\\G47.3 Sleep apnea\\"
        );

        String ambiguous = "\\Synthea\\ACT Diagnosis ICD-10\\G47.3 Sleep apnea\\";
        assertEquals(ambiguous, paths.snapped(ambiguous));
        String invented = "\\Synthea\\ACT Diagnosis ICD-10\\G47.5 Parasomnia\\";
        assertEquals(invented, paths.snapped(invented));
        String folder = "\\Synthea\\ACT Diagnosis ICD-10\\";
        assertEquals(folder, paths.snapped(folder));
    }

    @Test
    void normalizeLeavesNullAndBlankAlone() {
        assertNull(ConceptPaths.normalize(null));
        assertEquals("", ConceptPaths.normalize(""));
        assertEquals("  ", ConceptPaths.normalize("  "));
    }

    @Test
    void aPathFromAToolResultIsKnownInAnyBackslashVariant() {
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom(TOOL_RESULT);

        assertTrue(paths.isKnown(REAL));
        assertTrue(paths.isKnown("\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)"));
        assertFalse(paths.isKnown("\\\\demo\\\\sex"));
    }

    @Test
    void learnsFromSelectEntriesAndFromTheRequestsOwnQuery() throws Exception {
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom(new ObjectMapper().readTree("{\"select\":[\"\\\\demo\\\\age\\\\\"],\"phenotypicClause\":{\"conceptPath\":\"\\\\demo\\\\sex\\\\\"}}"));

        assertTrue(paths.isKnown("\\demo\\age\\"));
        assertTrue(paths.isKnown("\\demo\\sex\\"));
    }

    @Test
    void ignoresContentThatIsNotJson() {
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom("The dictionary service is unavailable right now.");
        paths.learnFrom((String) null);
        paths.learnFrom("");

        assertFalse(paths.isKnown("\\anything\\"));
    }

    @Test
    void firstUnseenInAcceptsSeenPathsInAnyVariantAndAtAnyDepth() {
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom(TOOL_RESULT);
        String nested = "{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[{\"phenotypicFilterType\":\"REQUIRED\","
            + "\"conceptPath\":\"\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\"}]}}}";
        String inSelect = "{\"query\":{\"select\":[\"\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\"]}}";

        assertNull(paths.firstUnseenIn(nested));
        assertNull(paths.firstUnseenIn(inSelect));
    }

    @Test
    void firstUnseenInReturnsAnInventedPathAsWritten() {
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom(TOOL_RESULT);

        // The placeholder-style path a small model copied from a tool example, with forward slashes.
        assertEquals(
            "phs999999/sex/",
            paths.firstUnseenIn("{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"phs999999/sex/\"}}}")
        );
        assertEquals("\\demo\\age\\", paths.firstUnseenIn("{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}"));
    }

    @Test
    void firstUnseenInIgnoresBlankPathsUnreadableArgumentsAndNoArguments() {
        ConceptPaths paths = new ConceptPaths();

        assertNull(paths.firstUnseenIn("{\"conceptPath\":\"\"}"));
        assertNull(paths.firstUnseenIn("not json at all"));
        assertNull(paths.firstUnseenIn("{\"query\":\"bmi\",\"pageSize\":25}"));
        assertNull(paths.firstUnseenIn(null));
        assertNull(paths.firstUnseenIn(""));
    }

    @Test
    void isMalformedFlagsArgumentsThatCannotBeParsedSoTheGuardCannotCheckThem() {
        // Single backslashes: \d is not a JSON escape and the trailing \" escapes the closing quote.
        assertTrue(ConceptPaths.isMalformed("{\"conceptPath\":\"\\demo\\age\\\"}"));
        assertTrue(ConceptPaths.isMalformed("not json at all"));
        assertFalse(ConceptPaths.isMalformed("{\"conceptPath\":\"\\\\demo\\\\age\\\\\"}"));
        assertFalse(ConceptPaths.isMalformed(null));
        assertFalse(ConceptPaths.isMalformed("  "));
    }
}
