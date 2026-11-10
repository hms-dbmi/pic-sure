package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyTextTest {

    @Test
    void plainProseIsUntouched() {
        assertEquals("Found 3 variables {see below}.", ReplyText.withoutQueryJson("Found 3 variables {see below}."));
        assertNull(ReplyText.withoutQueryJson(null));
    }

    @Test
    void aTrailingRecapOfTheScreenIsRemoved() {
        // As gpt-oss wrote them once every message ended with the screen state.
        assertEquals(
            "These variables are now shown in your table.",
            ReplyText.withoutScreenRecap(
                "These variables are now shown in your table.\n\n**Current view**\n- query: none\n- facets: none\n" + "- search: none"
            )
        );
        assertEquals(
            "D-dimer is not available.",
            ReplyText.withoutScreenRecap(
                "D-dimer is not available.\n\n---\n\nCurrent screen state:\n\n*query*: none\n*facets*: none\n" + "*search*: none"
            )
        );
        assertEquals(
            "About 1,200 participants.",
            ReplyText.withoutScreenRecap("About 1,200 participants.\n\nCurrent view: no filters, facets, or variable selections.")
        );
        assertEquals(
            "Your cohort is set.",
            ReplyText.withoutScreenRecap("Your cohort is set.\n\nCurrent screen:\n\n- **Selected variables:** BMI, SEX\n- **Facets:** none")
        );
    }

    @Test
    void aCurrentScreenHeadingFollowedByAnAnswerIsKept() {
        String answer = "**Current screen**\nThe table lists 12 laboratory variables, for example `\\a\\`.";
        String summary = "Done.\n\nYour current view now includes:\n- **Sex** filtered to Female";

        assertEquals(answer, ReplyText.withoutScreenRecap(answer));
        assertEquals(summary, ReplyText.withoutScreenRecap(summary));
        assertNull(ReplyText.withoutScreenRecap(null));
    }

    @Test
    void aClaimThatTheSelectedVariablesChangedIsToldApartFromAnOffer() {
        assertTrue(ReplyText.claimsSelectionChanged("Your selected variables now include the four alcohol-use questions you requested."));
        assertTrue(ReplyText.claimsSelectionChanged("I added the four questions to your selected variables."));
        assertTrue(ReplyText.claimsSelectionChanged("Found them. Your selection has been updated with BMI."));
        assertFalse(ReplyText.claimsSelectionChanged("Let me know if you'd like to add them to your selected variables."));
        assertFalse(ReplyText.claimsSelectionChanged("Your table already lists the alcohol variables."));
        // From T14 drafts: saying nothing changed is not a claim that something did.
        assertFalse(
            ReplyText.claimsSelectionChanged("No filters were added or removed, and none of your previously selected variables changed.")
        );
        assertFalse(
            ReplyText
                .claimsSelectionChanged("Your table now shows those variables, and no filters or selected variables have been changed.")
        );
        assertFalse(ReplyText.claimsSelectionChanged("I wasn't able to add any new variables to your selection."));
        assertFalse(ReplyText.claimsSelectionChanged(null));
    }

    @Test
    void pathsInProseGetAsciiHyphensAndSingleBackslashes() {
        // The path is bold, JSON-escaped, and has U+2011 where the dictionary has '-'.
        String reply = "| **\\\\Nhanes\\\\laboratory\\\\biochemistry\\\\LDL‑cholesterol (mg per dL)\\\\** | 20 – 316 |";

        assertEquals(
            "| **\\Nhanes\\laboratory\\biochemistry\\LDL-cholesterol (mg per dL)\\** | 20 – 316 |", ReplyText.withPlainPaths(reply)
        );
        assertEquals("`\\Nhanes\\demographics\\SEX\\`", ReplyText.withPlainPaths("`\\Nhanes\\demographics\\SEX\\`"));
        assertNull(ReplyText.withPlainPaths(null));
    }

    @Test
    void aKnownBacktickedPathMissingItsTrailingBackslashIsRestored() {
        // Every path in a long list lost its trailing backslash.
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom("{\"conceptPath\":\"\\\\Nhanes\\\\questionnaire\\\\smoking family\\\\Does anyone smoke in home?\\\\\"}");
        String reply = "* `\\Nhanes\\questionnaire\\smoking family\\Does anyone smoke in home?` - categorical";

        assertEquals(
            "* `\\Nhanes\\questionnaire\\smoking family\\Does anyone smoke in home?\\` - categorical",
            ReplyText.withKnownPaths(reply, paths)
        );
    }

    @Test
    void anUnknownOrTruncatedBacktickedPathIsLeftAsWritten() {
        // A truncated path must stay visibly wrong rather than be guessed at.
        ConceptPaths paths = new ConceptPaths();
        paths.learnFrom("{\"conceptPath\":\"\\\\Nhanes\\\\smoking behavior\\\\Do you now smoke cigarettes...\\\\\"}");
        String reply = "`\\Nhanes\\smoking behavior\\Do you now smoke cigarettes\u2026\\` and `plain code`";

        assertEquals(reply, ReplyText.withKnownPaths(reply, paths));
        assertNull(ReplyText.withKnownPaths(null, paths));
    }

    @Test
    void aJavaScriptStyleQueryObjectWithBareKeysAndALabelledSearchAreRemoved() {
        String reply =
            "query: {select:[\"\\\\Nhanes\\\\examination\\\\body measures\\\\Body Mass Index (kg per m**2)\\\\\"], phenotypicClause:{operator:\"AND\", "
                + "phenotypicClauses:[{phenotypicFilterType:\"FILTER\", conceptPath:\"\\\\Nhanes\\\\demographics\\\\SEX\", values:[\"female\"]}]}}\n\nsearch: \"sex\"";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void aCurrentStateRecapInAnyOfTheModelsLayoutsIsRemoved() {
        assertEquals(
            "Here are the two variables.",
            ReplyText.withoutScreenRecap(
                "Here are the two variables.\n\n---  \n\nCurrent state (unchanged):  \n**query:** none  \n**facets:** none  \n**search:** none"
            )
        );
        assertEquals(
            "Alcohol variables are listed.",
            ReplyText.withoutScreenRecap(
                "Alcohol variables are listed.\n\n---  \n**Current state**  \nquery: none  \nfacets: none  \nsearch: **alcohol** (updated)"
            )
        );
        assertEquals(
            "Current view of available data: BMI is available in study A.",
            ReplyText.withoutScreenRecap("Current view of available data: BMI is available in study A.")
        );
        assertEquals(
            "Current state of the cohort: 1,200 participants.\nMore text.",
            ReplyText.withoutScreenRecap("Current state of the cohort: 1,200 participants.\nMore text.")
        );
        assertEquals(
            "Done.\n\nCurrent state of the cohort is 1,200 participants.",
            ReplyText.withoutScreenRecap("Done.\n\nCurrent state of the cohort is 1,200 participants.")
        );
    }

    @Test
    void bulletedUndefinedFieldEchoesAndTheHeadingAboveThemAreRemoved() {
        String reply =
            "There are no variables returned for waist-hip ratio.\n\n**Current state of the interface**  \n- query: {}  \n- facets: undefined  \n- search: undefined";

        assertEquals("There are no variables returned for waist-hip ratio.", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void aHeadingLeftDanglingAfterTheScrubIsRemovedButAnUntouchedReplyKeepsItsLastLine() {
        String scrubbed = "**Variable found**\n\n- **Display name:** BMI\n\n---\n\n**Current query**\n{\"select\":[\"a\"]}";
        assertEquals("**Variable found**\n\n- **Display name:** BMI", ReplyText.withoutQueryJson(scrubbed));

        assertEquals("Summary\n\n**Note**", ReplyText.withoutQueryJson("Summary\n\n**Note**"));
    }

    @Test
    void aWholeReplyThatIsQueryJsonBecomesEmpty() {
        String reply =
            "{\"query\":{\"select\":[\"\\\\Nhanes\\\\bmi\\\\\"],\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":"
                + "[{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Nhanes\\\\SEX\\\\\",\"values\":[\"female\"]}]}},\"facets\":[],\"search\":\"\"}";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void labelledQueryJsonAndEmptyFacetsLinesAreRemoved() {
        String reply =
            "query: {\n  \"select\": [\"a\"],\n  \"phenotypicClause\": {\"phenotypicFilterType\": \"REQUIRED\", \"conceptPath\": \"x}y\"}\n}\nfacets: []";

        assertEquals("", ReplyText.withoutQueryJson(reply));
    }

    @Test
    void proseAroundQueryJsonIsKept() {
        String reply =
            "Here is your view.\n\n```json\n{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"a\"}}\n```\n\nIt shows women over 40.";

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
        String reply =
            "Here are the two variables.\n\n**Current query state**\n```json\n{\"query\":{\"select\":[\"a\"]},\"facets\":[],\"search\":\"\"}\n```";

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
