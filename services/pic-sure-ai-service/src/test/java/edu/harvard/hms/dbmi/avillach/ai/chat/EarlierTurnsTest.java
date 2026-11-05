package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EarlierTurnsTest {
    private static final int MAX = ProposeQueryInput.DEFAULT_MAX_FILTERS;


    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SEX =
        "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\sex\\\\\",\"values\":[\"female\"]}";
    private static final String AGE = "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"min\":40}";

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static ConversationTurn sent(String query) throws Exception {
        return new ConversationTurn("hi", "ok", List.of(), query == null ? null : json(query));
    }

    /** The request's query, validated as the loop does. */
    private static ProposeQueryInput onScreen(String query) throws Exception {
        return ChatStateValidator.requireValid(new ChatRequest("hi", "c", "r", json(query), null, null));
    }

    private static final String BOTH =
        "{\"select\":[\"\\\\demo\\\\sex\\\\\",\"\\\\demo\\\\age\\\\\"],\"phenotypicClause\":{\"operator\":\"AND\","
            + "\"phenotypicClauses\":[" + SEX + "," + AGE + "]}}";

    @Test
    void aFilterAndVariableTheResearcherRemovedAreNamed() throws Exception {
        String ageOnly = "{\"select\":[\"\\\\demo\\\\age\\\\\"],\"phenotypicClause\":" + AGE + "}";

        assertEquals(
            "filters on `\\demo\\sex\\`; selected variables `\\demo\\sex\\`",
            EarlierTurns.removedByHand(List.of(sent(BOTH)), onScreen(ageOnly), MAX)
        );
    }

    @Test
    void aClearedQueryNamesEverythingTheLastSentQueryHad() throws Exception {
        assertEquals(
            "filters on `\\demo\\sex\\`, `\\demo\\age\\`; selected variables `\\demo\\sex\\`, `\\demo\\age\\`",
            EarlierTurns.removedByHand(List.of(sent(BOTH)), null, MAX)
        );
    }

    @Test
    void nothingIsNamedWhenTheScreenStillHasTheSentQueryOrNoneWasSent() throws Exception {
        assertNull(EarlierTurns.removedByHand(List.of(sent(BOTH)), onScreen(BOTH), MAX));
        assertNull(EarlierTurns.removedByHand(List.of(sent(null)), null, MAX));
        assertNull(EarlierTurns.removedByHand(List.of(), null, MAX));
    }

    @Test
    void onlyTheLatestSentQueryCounts() throws Exception {
        String ageOnly = "{\"select\":[\"\\\\demo\\\\age\\\\\"],\"phenotypicClause\":" + AGE + "}";

        // The second turn removed SEX itself, and a turn that sent nothing does not reset what was last sent.
        assertNull(EarlierTurns.removedByHand(List.of(sent(BOTH), sent(ageOnly), sent(null)), onScreen(ageOnly), MAX));
    }
}
