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
