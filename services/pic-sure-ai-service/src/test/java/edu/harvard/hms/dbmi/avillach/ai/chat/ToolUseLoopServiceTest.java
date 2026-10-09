package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ConverseModelClient;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ModelTurnResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ModelUnavailableException;
import edu.harvard.hms.dbmi.avillach.ai.model.RequestedToolCall;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolUseLoopServiceTest {

    private static final ChatRequest REQUEST = new ChatRequest("hi", "conv-1", "req-1", null, null, null);
    private static final CallerContext CALLER = new CallerContext("Bearer test-jwt", "req-1", "user-1");
    private static final ProposeQueryTool PROPOSE_QUERY_TOOL = new ProposeQueryTool(new ObjectMapper());

    @Test
    void returnsTextOnlyResponseWhenModelEndsTurnImmediately() {
        FakeModelClient model = FakeModelClient.returning(doneTurn("Hello there"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Hello there", response.response());
        assertNull(response.query());
        assertNull(response.facets());
        assertNull(response.search());
        assertTrue(tools.calls.isEmpty(), "no tool should be called when the model never asks for one");
        assertEquals(1, model.callCount);
    }

    @Test
    void dispatchesDataLookupsToTheMcpGatewayButHandlesTheProposalToolLocally() {
        FakeModelClient model = FakeModelClient
            .returning(toolCallTurn("search_concepts", "{\"query\":\"bmi\"}"), toolCallTurn("propose_query", "{}"), doneTurn("Done"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Done", response.response());
        // propose_query never reaches the gateway -- it's handled locally (see ToolUseLoopService/ProposeQueryTool).
        assertEquals(List.of("search_concepts"), tools.calls.stream().map(c -> c.name).toList());
        assertEquals(CALLER, tools.calls.get(0).caller);
        // A proposal result is handled and fed back, but never promoted to the structured fields yet.
        assertNull(response.query());
    }

    @Test
    void aMalformedProposalIsFedBackAsAnErrorResultWithoutReachingTheGateway() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("propose_query", "{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\"}}}"), doneTurn("Handled the error")
        );
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Handled the error", response.response());
        assertTrue(tools.calls.isEmpty(), "a malformed proposal is never forwarded to the gateway");
        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(fedBack.content().contains("phenotypicClauses"), "the missing field must be named in the result fed back to the model");
    }

    @Test
    void aConceptPathNoSearchReturnedIsRefusedWithoutReachingTheGateway() {
        // The placeholder path a small model copied from a tool example, called before any search had returned.
        String invented = "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"phs999999/bmi/\"}}}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("count_participants", invented), doneTurn("Handled the error"));
        FakeToolGateway tools = new FakeToolGateway();

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertTrue(tools.calls.isEmpty(), "an invented path must never be forwarded to the gateway");
        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(fedBack.content().contains("phs999999/bmi/"), "the refused path is named: " + fedBack.content());
        assertTrue(fedBack.content().contains("search_concepts"), "the model is told to search first: " + fedBack.content());
    }

    @Test
    void aProposalWithAnInventedPathIsRefusedToo() {
        String invented = "{\"query\":{\"select\":[\"phs999999/bmi/\"]}}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", invented), doneTurn("Handled the error"));

        new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(fedBack.content().contains("Unknown concept path"), fedBack.content());
    }

    @Test
    void aPathFromAnEarlierSearchResultInTheSameTurnIsAllowedThrough() {
        String searchResult = "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\body\\\\bmi\\\\\"}]}";
        String countWithThatPath =
            "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\body\\\\bmi\\\\\"}}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"query\":\"bmi\"}"), toolCallTurn("count_participants", countWithThatPath), doneTurn("Done")
        );
        FakeToolGateway tools = FakeToolGateway.returning(searchResult);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(List.of("search_concepts", "count_participants"), tools.calls.stream().map(c -> c.name).toList());
    }

    @Test
    void aPathFromTheResearchersCurrentQueryIsAllowedThrough() throws Exception {
        ChatRequest request = new ChatRequest(
            "narrow it", "conv-1", "req-1", new ObjectMapper().readTree("{\"select\":[\"\\\\demo\\\\age\\\\\"]}"), null, null
        );
        String countWithThatPath = "{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("count_participants", countWithThatPath), doneTurn("Done"));
        FakeToolGateway tools = new FakeToolGateway();

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals(List.of("count_participants"), tools.calls.stream().map(c -> c.name).toList());
    }

    /** A search/facet result in one: the concept path {@code \demo\age\} and the facet study/demo. */
    private static final String DICTIONARY_RESULT =
        "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\age\\\\\"}],\"categories\":[{\"name\":\"study\",\"facets\":[{\"name\":\"demo\"}]}]}";
    private static final String AGE_QUERY =
        "{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\age\\\\\"}}";
    private static final String AGE_PATH = "\\demo\\age\\";

    @Test
    void aMalformedQueryOnTheRequestIsRefusedBeforeTheModelIsCalled() throws Exception {
        ChatRequest request = new ChatRequest("hi", "conv-1", "req-1", new ObjectMapper().readTree("\"not an object\""), null, null);
        FakeModelClient model = FakeModelClient.returning(doneTurn("unreachable"));

        PicsureException thrown =
            assertThrows(PicsureException.class, () -> new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, thrown.getStatus());
        assertTrue(thrown.getMessage().contains("query"), thrown.getMessage());
        assertEquals(0, model.callCount);
    }

    @Test
    void aMalformedFacetOrSearchOnTheRequestIsRefusedToo() throws Exception {
        ChatRequest badFacet = new ChatRequest("hi", "c", "r", null, List.of(new FacetSelection("study", " ")), null);
        ChatRequest badSearch = new ChatRequest("hi", "c", "r", null, null, "bad\nsearch");
        FakeModelClient model = FakeModelClient.returning(doneTurn("unreachable"));
        ToolUseLoopService service = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8);

        assertTrue(assertThrows(PicsureException.class, () -> service.handle(badFacet, CALLER)).getMessage().contains("facets"));
        assertTrue(assertThrows(PicsureException.class, () -> service.handle(badSearch, CALLER)).getMessage().contains("search"));
        assertEquals(0, model.callCount);
    }

    @Test
    void theUisFullQueryObjectWithFieldsTheModelMayNotSetIsAccepted() throws Exception {
        String full = "{\"select\":[],\"authorizationFilters\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":null,"
            + "\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"not\":false}}";
        ChatRequest request = new ChatRequest("hi", "c", "r", new ObjectMapper().readTree(full), null, null);
        FakeModelClient model = FakeModelClient.returning(doneTurn("ok"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals("ok", response.response());
    }

    @Test
    void anAcceptedQueryProposalSurfacesInTheResponseWithThePathAsTheToolReturnedIt() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"query\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(AGE_PATH, response.query().path("phenotypicClause").path("conceptPath").asText());
        assertEquals("REQUIRED", response.query().path("phenotypicClause").path("phenotypicFilterType").asText());
        assertFalse(response.query().has("genomicFilters"), "unset fields are not echoed as nulls");
        assertNull(response.facets());
        assertNull(response.search());
    }

    @Test
    void aProposalMatchingTheRequestsCurrentQueryIsOmittedAsUnchanged() throws Exception {
        ChatRequest request = new ChatRequest("same", "c", "r", new ObjectMapper().readTree(AGE_QUERY), null, null);
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY + "}"), doneTurn("Done"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertNull(response.query());
    }

    @Test
    void aFacetProposalNeedsAListFacetsResultAndIsOtherwiseNeverSurfaced() {
        String proposal = "{\"facets\":[{\"category\":\"study\",\"name\":\"demo\"}]}";
        FakeModelClient refused = FakeModelClient.returning(toolCallTurn("propose_query", proposal), doneTurn("Done"));
        FakeModelClient accepted =
            FakeModelClient.returning(toolCallTurn("list_facets", "{}"), toolCallTurn("propose_query", proposal), doneTurn("Done"));

        ChatResponse withoutListing =
            new ToolUseLoopService(refused, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);
        ChatResponse withListing =
            new ToolUseLoopService(accepted, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(withoutListing.facets());
        assertEquals(List.of(new FacetSelection("study", "demo")), withListing.facets());
    }

    @Test
    void aFacetProposalEqualToTheCurrentSelectionIsOmittedRegardlessOfOrder() {
        ChatRequest request =
            new ChatRequest("hi", "c", "r", null, List.of(new FacetSelection("study", "demo"), new FacetSelection("sex", "f")), null);
        String proposal = "{\"facets\":[{\"category\":\"sex\",\"name\":\"f\"},{\"category\":\"study\",\"name\":\"demo\"}]}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", proposal), doneTurn("Done"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertNull(response.facets());
    }

    @Test
    void aSearchProposalSurfacesOnlyWhenItDiffersFromTheRequests() throws Exception {
        ChatRequest same = new ChatRequest("hi", "c", "r", null, null, "bmi");
        String proposal = "{\"search\":\"bmi\"}";

        ChatResponse unchanged = new ToolUseLoopService(
            FakeModelClient.returning(toolCallTurn("propose_query", proposal), doneTurn("Done")), new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8
        ).handle(same, CALLER);
        ChatResponse changed = new ToolUseLoopService(
            FakeModelClient.returning(toolCallTurn("propose_query", proposal), doneTurn("Done")), new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8
        ).handle(REQUEST, CALLER);

        assertNull(unchanged.search());
        assertEquals("bmi", changed.search());
    }

    @Test
    void aRejectedProposalNeverSurfacesEvenIfALaterTurnEndsNormally() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("propose_query", "{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\"}}}"), doneTurn("Gave up")
        );

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Gave up", response.response());
        assertNull(response.query());
    }

    @Test
    void aLaterProposalReplacesEarlierOnesOnlyForThePartsItSends() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("propose_query", "{\"search\":\"first\"}"),
            toolCallTurn("propose_query", "{\"search\":\"second\",\"facets\":[]}"), doneTurn("Done")
        );

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("second", response.search());
    }

    @Test
    void theCurrentQueryFacetsAndSearchAreShownToTheModelAfterTheMessage() throws Exception {
        String uiQuery = "{\"authorizationFilters\":[\"secret\"],\"expectedResultType\":\"COUNT\"," + "\"phenotypicClause\":"
            + new ObjectMapper().readTree(AGE_QUERY).get("phenotypicClause") + "}";
        ChatRequest request = new ChatRequest(
            "narrow it", "c", "r", new ObjectMapper().readTree(uiQuery), List.of(new FacetSelection("study", "demo")), "age"
        );
        FakeModelClient model = FakeModelClient.returning(doneTurn("ok"));

        new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        String shown = ((ConversationEntry.UserEntry) model.historySeenOnCall(1).get(0)).text();
        assertTrue(shown.startsWith("narrow it"), shown);
        assertTrue(shown.contains("query: {\"phenotypicClause\":"), shown);
        assertTrue(shown.contains("facets: [{\"category\":\"study\",\"name\":\"demo\"}]"), shown);
        assertTrue(shown.contains("search: \"age\""), shown);
        assertFalse(shown.contains("secret"), "fields the model may not set are not passed along: " + shown);
    }

    @Test
    void aMessageWithNoCurrentStateIsSentUnchanged() {
        FakeModelClient model = FakeModelClient.returning(doneTurn("ok"));

        new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("hi", ((ConversationEntry.UserEntry) model.historySeenOnCall(1).get(0)).text());
    }

    @Test
    void stopsAtTheIterationCapAndReturnsTheBestAnswerSoFar() {
        FakeModelClient model = FakeModelClient
            .returning(toolCallTurn("search_concepts", "{}"), toolCallTurn("search_concepts", "{}"), toolCallTurn("search_concepts", "{}"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 3).handle(REQUEST, CALLER);

        assertEquals(3, model.callCount, "the cap bounds model calls, not just tool calls");
        assertEquals(2, tools.calls.size(), "only two rounds are dispatched before the third call hits the cap");
        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void aToolFailureIsFedBackAsAnErrorResultAndNeverThrows() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn("Handled the error"));
        FakeToolGateway tools = FakeToolGateway.thatFailsEveryCall();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Handled the error", response.response());
        ConversationEntry lastEntryOfFirstReplay = model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(lastEntryOfFirstReplay instanceof ConversationEntry.ToolResultEntry, "the failure must still appear as a tool result");
    }

    @Test
    void aModelFailureThrowsADistinctErrorFromAToolFailure() {
        FakeModelClient model = FakeModelClient.throwingImmediately();
        FakeToolGateway tools = new FakeToolGateway();

        PicsureException thrown =
            assertThrows(PicsureException.class, () -> new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER));

        assertEquals("model_unavailable", thrown.getErrorType());
        assertTrue(tools.calls.isEmpty(), "the model never got a chance to request a tool");
    }

    private static ModelTurnResult doneTurn(String text) {
        return new ModelTurnResult(true, text, List.of(), 10, 5);
    }

    private static ModelTurnResult toolCallTurn(String toolName, String argumentsJson) {
        return new ModelTurnResult(false, null, List.of(new RequestedToolCall("call-" + toolName, toolName, argumentsJson)), 10, 5);
    }

    /** Records every conversation it was sent, in call order, so a test can inspect what the loop replayed. */
    private static final class FakeModelClient implements ConverseModelClient {

        private final Deque<ModelTurnResult> turns;
        private final boolean throwsOnFirstCall;
        private final List<List<ConversationEntry>> historiesSeen = new ArrayList<>();
        int callCount;

        private FakeModelClient(Deque<ModelTurnResult> turns, boolean throwsOnFirstCall) {
            this.turns = turns;
            this.throwsOnFirstCall = throwsOnFirstCall;
        }

        static FakeModelClient returning(ModelTurnResult... turns) {
            Deque<ModelTurnResult> queue = new ArrayDeque<>(List.of(turns));
            return new FakeModelClient(queue, false);
        }

        static FakeModelClient throwingImmediately() {
            return new FakeModelClient(new ArrayDeque<>(), true);
        }

        @Override
        public ModelTurnResult send(String systemPrompt, List<ConversationEntry> history, List<ToolDefinition> tools) {
            callCount++;
            historiesSeen.add(List.copyOf(history));
            if (throwsOnFirstCall) {
                throw new ModelUnavailableException("boom", new RuntimeException("boom"));
            }
            return turns.size() > 1 ? turns.poll() : turns.peek();
        }

        List<ConversationEntry> historySeenOnCall(int callNumber) {
            return historiesSeen.get(callNumber - 1);
        }
    }

    /** Records every tool call it received, so a test can assert dispatch went through this gateway and nowhere else. */
    private static final class FakeToolGateway implements McpToolGateway {

        record Call(String name, String argumentsJson, CallerContext caller) {
        }

        private final List<Call> calls = new ArrayList<>();
        private final boolean failEveryCall;
        private final String successContent;

        FakeToolGateway() {
            this(false, "{}");
        }

        private FakeToolGateway(boolean failEveryCall, String successContent) {
            this.failEveryCall = failEveryCall;
            this.successContent = successContent;
        }

        static FakeToolGateway thatFailsEveryCall() {
            return new FakeToolGateway(true, "{}");
        }

        /** A gateway whose every successful call returns the given content, e.g. a search result carrying concept paths. */
        static FakeToolGateway returning(String successContent) {
            return new FakeToolGateway(false, successContent);
        }

        @Override
        public List<ToolDefinition> listTools(CallerContext caller) {
            return List.of();
        }

        @Override
        public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
            calls.add(new Call(name, argumentsJson, caller));
            if (failEveryCall) {
                throw new RuntimeException("mock gateway failure");
            }
            return ToolResult.success(successContent);
        }
    }
}
