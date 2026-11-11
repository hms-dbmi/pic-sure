package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theModelSeesEachEarlierMessageAndFinalReplyAndNoLookups() throws Exception {
        ConversationTurn first = new ConversationTurn(
            "find age", "Found age", List.of(new ConversationTurn.ToolOutput("search_concepts", "{}", "{\"concepts\":[]}")), null
        );
        ConversationTurn second = new ConversationTurn("and sex?", "Found sex", List.of(), null);

        List<ConversationEntry> entries = EarlierTurns.entries(List.of(first, second));

        assertEquals(4, entries.size(), entries.toString());
        assertEquals("find age", ((ConversationEntry.UserEntry) entries.get(0)).text());
        ConversationEntry.AssistantToolCallEntry reply = (ConversationEntry.AssistantToolCallEntry) entries.get(1);
        assertEquals("Found age", reply.text());
        assertTrue(reply.toolCalls().isEmpty());
        assertEquals("and sex?", ((ConversationEntry.UserEntry) entries.get(2)).text());
        assertEquals("Found sex", ((ConversationEntry.AssistantToolCallEntry) entries.get(3)).text());
        assertTrue(EarlierTurns.entries(List.of()).isEmpty());
    }

    @Test
    void storedLookupsRebuildPathsFacetsAndUnmatchedTerms() {
        TurnState state = new TurnState(null);
        ConversationTurn search = new ConversationTurn(
            "hi", "ok",
            List.of(
                new ConversationTurn.ToolOutput(
                    ToolUseLoopService.SEARCH_CONCEPTS, "{\"search\":\"age\"}", "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\age\\\\\"}]}"
                ),
                new ConversationTurn.ToolOutput(
                    ToolUseLoopService.LIST_FACETS, "{}", "{\"categories\":[{\"name\":\"study\",\"facets\":[{\"name\":\"demo\"}]}]}"
                )
            ), null
        );

        EarlierTurns.learnInto(state, List.of(search));

        assertTrue(state.conceptPaths.isKnown("\\demo\\age\\"));
        assertTrue(state.knownFacets.isKnown(new FacetSelection("study", "demo")));
        assertFalse(state.knownFacets.isKnown(new FacetSelection("study", "other")));
        assertFalse(state.conceptPaths.isKnown("\\demo\\sex\\"));
    }
}
