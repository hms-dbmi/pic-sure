package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplyTextTest {

    @Test
    void plainProseIsUntouched() {
        assertEquals("Found 3 variables {see below}.", ReplyText.withoutQueryJson("Found 3 variables {see below}."));
        assertNull(ReplyText.withoutQueryJson(null));
    }

    @Test
    void pathsInProseGetAsciiHyphensAndSingleBackslashes() {
        // Round 8, T3 R1: the path is bold, JSON-escaped, and has U+2011 where the dictionary has '-'.
        String reply = "| **\\\\Nhanes\\\\laboratory\\\\biochemistry\\\\LDL‑cholesterol (mg per dL)\\\\** | 20 – 316 |";

        assertEquals("| **\\Nhanes\\laboratory\\biochemistry\\LDL-cholesterol (mg per dL)\\** | 20 – 316 |", ReplyText.withPlainPaths(reply));
        assertEquals("`\\Nhanes\\demographics\\SEX\\`", ReplyText.withPlainPaths("`\\Nhanes\\demographics\\SEX\\`"));
        assertNull(ReplyText.withPlainPaths(null));
    }

    @Test
    void aKnownBacktickedPathMissingItsTrailingBackslashIsRestored() {
        // Round 9, T14 R2: every path in a long list lost its trailing backslash.
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom("{\"conceptPath\":\"\\\\Nhanes\\\\questionnaire\\\\smoking family\\\\Does anyone smoke in home?\\\\\"}");
        String reply = "* `\\Nhanes\\questionnaire\\smoking family\\Does anyone smoke in home?` - categorical";

        assertEquals(
            "* `\\Nhanes\\questionnaire\\smoking family\\Does anyone smoke in home?\\` - categorical", ReplyText.withKnownPaths(reply, paths)
        );
    }

    @Test
    void anUnknownOrTruncatedBacktickedPathIsLeftAsWritten() {
        // Round 9, T14 R1: a truncated path must stay visibly wrong rather than be guessed at.
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom("{\"conceptPath\":\"\\\\Nhanes\\\\smoking behavior\\\\Do you now smoke cigarettes...\\\\\"}");
        String reply = "`\\Nhanes\\smoking behavior\\Do you now smoke cigarettes\u2026\\` and `plain code`";

        assertEquals(reply, ReplyText.withKnownPaths(reply, paths));
        assertNull(ReplyText.withKnownPaths(null, paths));
    }

    @Test
    void aJavaScriptStyleQueryObjectWithBareKeysAndALabelledSearchAreRemoved() {
        // Round 3, T7 R2.
        String reply = "query: {select:[\"\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\"], phenotypicClause:{operator:\"AND\", "
            + "phenotypicClauses:[{phenotypicFilterType:\"FILTER\", conceptPath:\"\\\\Nhanes\\\\demographics\\\\SEX\", values:[\"female\"]}]}}\n\nsearch: \"sex\"";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void bulletedUndefinedFieldEchoesAndTheHeadingAboveThemAreRemoved() {
        // Round 3, T2 R1.
        String reply = "There are no variables returned for waist-hip ratio.\n\n**Current state of the interface**  \n- query: {}  \n- facets: undefined  \n- search: undefined";

        assertEquals("There are no variables returned for waist-hip ratio.", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void aHeadingLeftDanglingAfterTheScrubIsRemovedButAnUntouchedReplyKeepsItsLastLine() {
        // Round 3, T1 R2.
        String scrubbed = "**Variable found**\n\n- **Display name:** BMI\n\n---\n\n**Current query**\n{\"select\":[\"a\"]}";
        assertEquals("**Variable found**\n\n- **Display name:** BMI", ReplyText.withoutQueryJson(scrubbed));

        assertEquals("Summary\n\n**Note**", ReplyText.withoutQueryJson("Summary\n\n**Note**"));
    }

    @Test
    void aWholeReplyThatIsQueryJsonBecomesEmpty() {
        String reply = "{\"query\":{\"select\":[\"\\\\Nhanes\\\\bmi\\\\\"],\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":"
            + "[{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Nhanes\\\\SEX\\\\\",\"values\":[\"female\"]}]}},\"facets\":[],\"search\":\"\"}";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void labelledQueryJsonAndEmptyFacetsLinesAreRemoved() {
        String reply = "query: {\n  \"select\": [\"a\"],\n  \"phenotypicClause\": {\"phenotypicFilterType\": \"REQUIRED\", \"conceptPath\": \"x}y\"}\n}\nfacets: []";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void proseAroundQueryJsonIsKept() {
        String reply = "Here is your view.\n\n```json\n{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"a\"}}\n```\n\nIt shows women over 40.";

        assertEquals("Here is your view.\n\nIt shows women over 40.", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void unrelatedJsonAndCodeFencesAreKept() {
        String reply = "Range:\n```json\n{\"min\": 1, \"max\": 2}\n```";

        assertEquals(reply, ReplyText.withoutQueryJson(reply));
    }

    @Test
    void aSelectOnlyQueryObjectAndItsLabelsAreRemoved() {
        String reply = "query: {\"select\":[\"\\\\Nhanes\\\\bmi\\\\\"]} facets: [] search: \"\"";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void aFencedSelectOnlyQueryIsRemovedAndTheProseAroundItKept() {
        String reply = "Here are the two variables.\n\n**Current query state**\n```json\n{\"query\":{\"select\":[\"a\"]},\"facets\":[],\"search\":\"\"}\n```";

        // The bold label above the fence is left dangling once the query is gone, so it goes too.
        assertEquals("Here are the two variables.", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void labelsLeftOverOnOneLineAfterTheObjectIsGoneAreRemoved() {
        assertEquals("", ReplyText.withoutQueryJson("query:  facets: [] search: \"\""));
    }

    @Test
    void aReplyThatIsOnlyALabelWithAValueOrALoneBraceIsEmpty() {
        assertEquals("", ReplyText.withoutQueryJson("search: \"sex\""));
        assertEquals("", ReplyText.withoutQueryJson("}"));
    }

    @Test
    void proseThatMentionsASearchIsKept() {
        String reply = "I used search: body mass index and found one match.";

        assertEquals(reply, ReplyText.withoutQueryJson(reply));
    }

    @Test
    void aReplyThatIsOnlyProposeQueryArgumentsIsReadAsThem() {
        // Round 5, T9 R1.
        String reply = "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\a\\\\\"}}}";

        assertEquals(reply, ReplyText.proposalArguments(reply));
        assertEquals(reply, ReplyText.proposalArguments("```json\n" + reply + "\n```"));
    }

    @Test
    void aBareQueryReplyIsWrappedAsTheQueryArgument() {
        assertEquals("{\"query\":{\"select\":[\"a\"]}}", ReplyText.proposalArguments(" {\"select\":[\"a\"]} "));
    }

    @Test
    void proseOrAnyOtherJsonIsNotAProposal() {
        assertNull(ReplyText.proposalArguments(null));
        assertNull(ReplyText.proposalArguments("Found one variable."));
        assertNull(ReplyText.proposalArguments("{\"query\":{\"select\":[\"a\"]}} Here is your query."));
        assertNull(ReplyText.proposalArguments("{\"answer\":\"none\"}"));
        assertNull(ReplyText.proposalArguments("[\"a\"]"));
    }
}
