package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClientException;

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
            .returning(toolCallTurn("search_concepts", "{\"search\":\"bmi\"}"), toolCallTurn("propose_query", "{}"), doneTurn("Done"));
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
            toolCallTurn("search_concepts", "{\"search\":\"bmi\"}"), toolCallTurn("count_participants", countWithThatPath), doneTurn("Done")
        );
        FakeToolGateway tools = FakeToolGateway.returning(searchResult);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(List.of("search_concepts", "count_participants"), tools.calls.stream().map(c -> c.name).toList());
    }

    @Test
    void aCategoricalValueInTheWrongCaseIsForwardedAsTheSearchListedIt() {
        String searchResult = "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\sex\\\\\",\"values\":[\"female\",\"male\"]}]}";
        String countWithCapital = "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\","
            + "\"conceptPath\":\"\\\\demo\\\\sex\\\\\",\"values\":[\"Female\"]}}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"sex\"}"), toolCallTurn("count_participants", countWithCapital), doneTurn("Done")
        );
        FakeToolGateway tools = FakeToolGateway.returning(searchResult);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(countWithCapital.replace("Female", "female"), tools.calls.get(1).argumentsJson());
    }

    @Test
    void aBackslashVariantOfASeenPathIsForwardedAsTheSearchReturnedIt() {
        String searchResult = "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\body\\\\bmi\\\\\"}]}";
        String countWithVariant =
            "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"demo\\\\body\\\\bmi\"}}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"bmi\"}"), toolCallTurn("count_participants", countWithVariant), doneTurn("Done")
        );
        FakeToolGateway tools = FakeToolGateway.returning(searchResult);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(
            "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\body\\\\bmi\\\\\"}}}",
            tools.calls.get(1).argumentsJson()
        );
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

    @Test
    void aPathOnlyInTheCurrentQuerysHiddenFieldsIsNotSeen() throws Exception {
        ChatRequest request = new ChatRequest(
            "narrow it", "conv-1", "req-1",
            new ObjectMapper().readTree("{\"authorizationFilters\":[{\"conceptPath\":\"\\\\secret\\\\consent\\\\\",\"values\":[\"x\"]}]}"),
            null, null
        );
        String countWithThatPath = "{\"query\":{\"select\":[\"\\\\secret\\\\consent\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("count_participants", countWithThatPath), doneTurn("Done"));
        FakeToolGateway tools = new FakeToolGateway();

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertTrue(tools.calls.isEmpty(), "a path the model was never shown must not reach the gateway");
    }

    /** A search/facet result in one: the concept path {@code \demo\age\} and the facet study/demo. */
    private static final String DICTIONARY_RESULT =
        "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\age\\\\\"}],\"categories\":[{\"name\":\"study\",\"facets\":[{\"name\":\"demo\"}]}]}";
    private static final String AGE_QUERY =
        "{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\age\\\\\"}}";
    private static final String AGE_PATH = "\\demo\\age\\";
    private static final String RACE_RESULT = "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\race\\\\\"}]}";
    private static final String SLEEP_RESULT = "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\G47 Sleep disorders\\\\\","
        + "\"values\":[\"G47.3 Sleep apnea\",\"G47.9 Sleep disorder, unspecified\"]}]}";

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
    void aSearchThatIsNotAStringOnTheRequestIsRefused() {
        for (JsonNode search : List.of(IntNode.valueOf(42), DoubleNode.valueOf(4.5), BooleanNode.TRUE, BooleanNode.FALSE)) {
            ChatRequest request = new ChatRequest("hi", "c", "r", null, null, search);
            FakeModelClient model = FakeModelClient.returning(doneTurn("unreachable"));
            ToolUseLoopService service = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8);

            PicsureException refused = assertThrows(PicsureException.class, () -> service.handle(request, CALLER), search.toString());
            assertTrue(refused.getMessage().contains("search"), refused.getMessage());
            assertEquals(0, model.callCount, search.toString());
        }
    }

    /** A valid query with twelve filters under one subquery: over {@link ProposeQueryInput#MAX_FILTERS}. */
    private static ChatRequest oversizedQueryRequest() throws Exception {
        String clauses = String.join(",", java.util.Collections.nCopies(12,
            "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"values\":[\"x\"]}"));
        String big = "{\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[" + clauses + "]}}";
        return new ChatRequest("narrow it", "c", "r", new ObjectMapper().readTree(big), null, null);
    }

    @Test
    void anOversizedResearcherQueryIsWithheldFromTheModelAndQueryProposalsAreRefused() throws Exception {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", "{\"query\":{}}"), doneTurn("Make it smaller"));

        ChatResponse response =
            new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(oversizedQueryRequest(), CALLER);

        String shown = ((ConversationEntry.UserEntry) model.historySeenOnCall(1).get(0)).text();
        assertTrue(shown.contains("too large to show"), shown);
        assertFalse(shown.contains("phenotypicClauses"), shown);
        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(fedBack.content().contains("too large"), "the refusal reaches the model: " + fedBack.content());
        assertNull(response.query());
    }

    @Test
    void aPathFromAnOversizedQueryTheModelWasNeverShownIsNotAllowedThrough() throws Exception {
        String countWithThatPath = "{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("count_participants", countWithThatPath), doneTurn("Done"));
        FakeToolGateway tools = new FakeToolGateway();

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(oversizedQueryRequest(), CALLER);

        assertTrue(tools.calls.isEmpty(), "a path only in the withheld query must not reach the gateway");
        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(fedBack.content().contains("Unknown concept path"), fedBack.content());
    }

    @Test
    void aMalformedFacetOrSearchOnTheRequestIsRefusedToo() throws Exception {
        ChatRequest badFacet = new ChatRequest("hi", "c", "r", null, List.of(new FacetSelection("study", " ")), null);
        ChatRequest badSearch = new ChatRequest("hi", "c", "r", null, null, TextNode.valueOf("bad\nsearch"));
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
    void anExclusionInTheResearchersQueryIsShownToTheModelAndKeptWhenItsFiltersAreCarriedOver() throws Exception {
        String excluding = "{\"select\":[],\"authorizationFilters\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\","
            + "\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"not\":true}}";
        ChatRequest request = new ChatRequest("add race to my columns", "c", "r", new ObjectMapper().readTree(excluding), null, null);
        // A select-only proposal: the loop carries the researcher's filters over (ProposalRules.keepingFilters).
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
            toolCallTurn("propose_query", "{\"query\":{\"select\":[\"\\\\demo\\\\race\\\\\"]}}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertTrue(((ConversationEntry.UserEntry) model.historySeenOnCall(1).get(0)).text().contains("\"not\":true"));
        assertTrue(response.query().path("phenotypicClause").path("not").asBoolean(false), response.query().toString());
    }

    @Test
    void aProposalDifferingOnlyByAnOmittedNotFalseIsOmittedAsUnchanged() throws Exception {
        String withNotFalse = "{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"not\":false}}";
        ChatRequest request = new ChatRequest("same", "c", "r", new ObjectMapper().readTree(withNotFalse), null, null);
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY + "}"), doneTurn("Done"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertNull(response.query());
    }

    @Test
    void aProposalDifferingOnlyByASubquerysOmittedNotFalseIsOmittedAsUnchanged() throws Exception {
        String filter = "{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\age\\\\\"}";
        String current = "{\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[" + filter + "],\"not\":false}}";
        String proposed = "{\"query\":{\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[" + filter + "]}}}";
        ChatRequest request = new ChatRequest("same", "c", "r", new ObjectMapper().readTree(current), null, null);
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", proposed), doneTurn("Done"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertNull(response.query());
    }

    @Test
    void theUisEmptyStartingQueryIsAcceptedButNotShownToTheModel() throws Exception {
        String empty = "{\"select\":[],\"authorizationFilters\":[],\"phenotypicClause\":null,\"genomicFilters\":[],"
            + "\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}";
        ChatRequest request = new ChatRequest("hi", "c", "r", new ObjectMapper().readTree(empty), null, null);
        FakeModelClient model = FakeModelClient.returning(doneTurn("ok"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals("ok", response.response());
        assertEquals("hi", ((ConversationEntry.UserEntry) model.historySeenOnCall(1).get(0)).text());
    }

    @Test
    void anAcceptedQueryProposalSurfacesInTheResponseWithThePathAsTheToolReturnedIt() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(AGE_PATH, response.query().path("phenotypicClause").path("conceptPath").asText());
        assertEquals("REQUIRED", response.query().path("phenotypicClause").path("phenotypicFilterType").asText());
        assertFalse(response.query().has("genomicFilters"), "unset fields are not echoed as nulls");
        assertFalse(response.query().has("empty"), "isEmpty() is not a query field");
        assertNull(response.facets());
        assertNull(response.search());
    }

    @Test
    void queryJsonTheModelWritesIntoItsReplyIsScrubbedAndReplacedWhenNothingElseIsLeft() {
        String leaked = "{\"query\":" + AGE_QUERY + ",\"facets\":[],\"search\":\"\"}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn(leaked)
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(ReplyText.PROPOSAL_ATTACHED_TEXT, response.response());
        assertEquals(AGE_PATH, response.query().path("phenotypicClause").path("conceptPath").asText());
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

        FakeToolGateway noFacets = new FakeToolGateway();
        ChatResponse withoutListing = new ToolUseLoopService(refused, noFacets, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);
        ChatResponse withListing =
            new ToolUseLoopService(accepted, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(withoutListing.facets(), "the lookup the service made itself did not return the facet");
        assertEquals("list_facets", noFacets.calls.get(0).name());
        assertEquals(List.of(new FacetSelection("study", "demo")), withListing.facets());
    }

    @Test
    void aFacetProposalWithoutAListFacetsCallIsCheckedAgainstALookupTheServiceMakes() {
        String proposal = "{\"facets\":[{\"category\":\"study\",\"name\":\"demo\"}],\"search\":\"age\"}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", proposal), doneTurn("Done"));
        FakeToolGateway tools = FakeToolGateway.returning(DICTIONARY_RESULT);

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(List.of(new FacetSelection("study", "demo")), response.facets());
        assertEquals("age", response.search());
        assertEquals(1, tools.calls.size());
        assertEquals("list_facets", tools.calls.get(0).name());
        assertEquals("{\"search\":\"age\"}", tools.calls.get(0).argumentsJson(), "the lookup uses the proposal's search text");
    }

    @Test
    void aFailedFacetLookupRefusesTheUnknownFacetRatherThanReportingTheProposalAsFailed() {
        String guessed = "{\"facets\":[{\"category\":\"study\",\"name\":\"guess\"}]}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", guessed), doneTurn("Done"));

        new ToolUseLoopService(model, FakeToolGateway.thatFailsEveryCall(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        String result = ((ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).getLast()).content();
        assertTrue(result.contains("Unknown facet"), result);
        assertTrue(result.contains("Call list_facets first"), "with no facets to list, the model is sent to look them up: " + result);
        assertFalse(result.contains("lookup failed"), result);
    }

    @Test
    void theServiceLooksUpFacetsAtMostOnceATurn() {
        String guessed = "{\"facets\":[{\"category\":\"study\",\"name\":\"guess\"}]}";
        FakeModelClient model =
            FakeModelClient.returning(toolCallTurn("propose_query", guessed), toolCallTurn("propose_query", guessed), doneTurn("Done"));
        FakeToolGateway tools = FakeToolGateway.returning(DICTIONARY_RESULT);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(1, tools.calls.size());
    }

    private static final String NARROWED_SEARCH = "{\"search\":\"age\",\"facets\":[{\"category\":\"study\",\"name\":\"demo\"}]}";

    @Test
    void aSearchNarrowedByAFacetTheModelWasNeverShownIsRefusedWithoutReachingTheDictionary() {
        String guessed = "{\"search\":\"age\",\"facets\":[{\"category\":\"study\",\"name\":\"guess\"}]}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", guessed), doneTurn("Done"));
        FakeToolGateway tools = FakeToolGateway.returning(DICTIONARY_RESULT);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(1, tools.calls.size(), "only the service's own list_facets lookup ran");
        assertEquals("list_facets", tools.calls.get(0).name());
        String refusal = lastToolResult(model, 2);
        assertTrue(refusal.contains("Unknown facet"), refusal);
        // The facets the service looked up, so the model can fix the call without spending one on list_facets.
        assertTrue(refusal.contains("(category: names): study: demo. Call the tool again"), refusal);
    }

    @Test
    void aSearchNarrowedByAListedFacetReachesTheDictionaryWithTheFacetsUnchanged() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", NARROWED_SEARCH), doneTurn("Done")
        );
        FakeToolGateway tools = FakeToolGateway.returning(DICTIONARY_RESULT);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(2, tools.calls.size(), "the model listed facets itself, so the service did not");
        assertEquals("search_concepts", tools.calls.get(1).name());
        assertEquals(NARROWED_SEARCH, tools.calls.get(1).argumentsJson());
    }

    @Test
    void aSearchOnlyProposalAfterAFacetNarrowedSearchCarriesThoseFacets() {
        // The model read NHANES-only results, but proposing just the search would show every dataset's variables in the table.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", NARROWED_SEARCH),
            toolCallTurn("propose_query", "{\"search\":\"age\"}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
        assertEquals(List.of(new FacetSelection("study", "demo")), response.facets());
    }

    private static final String FACET_ONLY_SEARCH = "{\"search\":\"\",\"facets\":[{\"category\":\"study\",\"name\":\"demo\"}]}";

    @Test
    void aFacetOnlyBrowseProposedWithJustTheFacetsKeepsNoSearch() {
        // The model had to invent a query because the tool wanted one; with none it proposes the facets alone.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", FACET_ONLY_SEARCH),
            toolCallTurn("propose_query", "{\"facets\":[{\"category\":\"study\",\"name\":\"demo\"}]}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(List.of(new FacetSelection("study", "demo")), response.facets());
        assertNull(response.search());
    }

    @Test
    void aScreenClaimAfterAFacetOnlyBrowseAttachesTheFacetsAndNoSearch() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", FACET_ONLY_SEARCH),
            doneTurn("These variables are now displayed in your results table.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(List.of(new FacetSelection("study", "demo")), response.facets());
        assertNull(response.search());
    }

    @Test
    void aFacetOnlyBrowseDoesNotAttachItsFacetsToAProposalWithASearchTerm() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", FACET_ONLY_SEARCH),
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("propose_query", "{\"search\":\"age\"}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
        assertNull(response.facets(), "the later search ran without facets");
    }

    @Test
    void aCohortQueryProposalNeverPicksUpTheFacetsOfAnEarlierBrowse() {
        String cohort = "{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"],\"phenotypicClause\":" + AGE_CLAUSE + "}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", FACET_ONLY_SEARCH), toolCallTurn("propose_query", cohort),
            doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.facets());
    }

    private static final String AGE_CLAUSE = "{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demo\\\\age\\\\\"}";

    @Test
    void aProposalThatSendsItsOwnFacetsKeepsThemEvenAfterAFacetNarrowedSearch() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", NARROWED_SEARCH),
            toolCallTurn("propose_query", "{\"search\":\"age\",\"facets\":[]}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
        assertNull(response.facets(), "an empty selection the model sent is not replaced");
    }

    @Test
    void aProposalForATermTheFacetNarrowedSearchNeverCoveredGetsNoFacets() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", NARROWED_SEARCH),
            toolCallTurn("search_concepts", "{\"search\":\"sex\"}"), toolCallTurn("propose_query", "{\"search\":\"sex\"}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("sex", response.search());
        assertNull(response.facets());
    }

    @Test
    void aScreenClaimAfterAFacetNarrowedSearchAttachesTheSearchAndItsFacets() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", NARROWED_SEARCH),
            doneTurn("These variables are now displayed in your results table.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
        assertEquals(List.of(new FacetSelection("study", "demo")), response.facets());
    }

    @Test
    void aSearchOnlyProposalForALookupSurfacesWithNoQuery() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("propose_query", "{\"search\":\"age\"}"),
            doneTurn("One variable matched: `\\demo\\age`.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
        assertNull(response.query());
        assertEquals("One variable matched: `" + AGE_PATH + "`.", response.response(), "the dropped trailing backslash is put back");
    }

    private static final String NO_MATCH_RESULT = "{\"page\":0,\"total\":0,\"concepts\":[]}";

    @Test
    void aSearchProposalWithATermThatMatchedNothingIsAcceptedWithThatTermDropped() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"BMI\"}"), toolCallTurn("propose_query", "{\"search\":\"BMI | age\"}"),
            doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(NO_MATCH_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
    }

    @Test
    void aTermThatMatchedNothingInATermsSearchIsDroppedFromTheProposal() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"terms\":[\"BMI\"]}"), toolCallTurn("propose_query", "{\"search\":\"BMI | age\"}"),
            doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(NO_MATCH_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
    }

    @Test
    void aSearchNarrowedByAnUnlistedFacetLooksFacetsUpWithItsSearchText() {
        String narrowed = "{\"terms\":[\"age\",\"sex\"],\"facets\":[{\"category\":\"study\",\"name\":\"demo\"}]}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", narrowed), doneTurn("Done"));
        FakeToolGateway tools = FakeToolGateway.returning(DICTIONARY_RESULT);

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("list_facets", tools.calls.get(0).name());
        assertEquals("{\"search\":\"age | sex\"}", tools.calls.get(0).argumentsJson());
    }

    @Test
    void aSearchProposalWhoseTermsAllMatchedNothingIsRefusedUntilSomethingMatches() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"BMI\"}"), toolCallTurn("propose_query", "{\"search\":\"BMI\"}"),
            toolCallTurn("propose_query", "{\"search\":\"BMI\"}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(NO_MATCH_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.search());
    }

    @Test
    void aListFacetsResultWithNoCategoriesCarriesANoteTellingTheModelNotToInventAny() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("list_facets", "{\"search\":\"\"}"), doneTurn("Done"));

        new ToolUseLoopService(model, FakeToolGateway.returning("{\"search\":\"\",\"categories\":[]}"), PROPOSE_QUERY_TOOL, 8)
            .handle(REQUEST, CALLER);

        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(fedBack.content().contains("\"categories\":[]"), "the result itself is kept");
        assertTrue(fedBack.content().contains("Do not invent"), "the note is added");
    }

    @Test
    void aListFacetsResultWithCategoriesIsPassedThroughUnchanged() {
        String result = "{\"search\":\"\",\"categories\":[{\"name\":\"study\",\"facets\":[{\"name\":\"demo\"}]}]}";
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("list_facets", "{\"search\":\"\"}"), doneTurn("Done"));

        new ToolUseLoopService(model, FakeToolGateway.returning(result), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        ConversationEntry.ToolResultEntry fedBack =
            (ConversationEntry.ToolResultEntry) model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertEquals(result, fedBack.content());
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
        ChatRequest same = new ChatRequest("hi", "c", "r", null, null, TextNode.valueOf("bmi"));
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
            "narrow it", "c", "r", new ObjectMapper().readTree(uiQuery), List.of(new FacetSelection("study", "demo")), TextNode.valueOf("age")
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
    void aModelThatKeepsCallingToolsAfterTheCapNoticeGetsTheFallback() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 3).handle(REQUEST, CALLER);

        assertEquals(4, model.callCount, "the cap bounds model calls, not just tool calls: the cap plus one last call to answer");
        assertEquals(2, tools.calls.size(), "only two rounds are dispatched before the third call hits the cap");
        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void hittingTheCapAsksTheModelForAnAnswerInsteadOfRunningItsPendingCalls() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"infant\"}"), toolCallTurn("search_concepts", "{\"search\":\"pediatric\"}"),
            doneTurn("No pediatric variables matched.")
        );
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 2).handle(REQUEST, CALLER);

        assertEquals("No pediatric variables matched.", response.response());
        assertEquals(1, tools.calls.size(), "the call pending at the cap is answered with the notice, not run");
        List<ConversationEntry> lastHistory = model.historySeenOnCall(3);
        ConversationEntry notice = lastHistory.get(lastHistory.size() - 1);
        assertEquals(ToolUseLoopService.CAP_REACHED_RESULT, ((ConversationEntry.ToolResultEntry) notice).content());
    }

    @Test
    void aToolFailureIsFedBackAsAnErrorResultAndNeverThrows() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn("Handled the error"));
        FakeToolGateway tools = FakeToolGateway.thatFailsEveryCall();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Handled the error", response.response());
        ConversationEntry lastEntryOfFirstReplay = model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(lastEntryOfFirstReplay instanceof ConversationEntry.ToolResultEntry, "the failure must still appear as a tool result");
        assertTrue(((ConversationEntry.ToolResultEntry) lastEntryOfFirstReplay).error(), "the failure must be flagged as an error to the model");
    }

    @Test
    void aToolFailingTheSameWayTwiceGetsANoteThatRetryingWillNotHelp() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"sex\"}"), toolCallTurn("search_concepts", "{\"search\":\"sex\",\"pageSize\":25}"),
            doneTurn("Handled the error")
        );
        FakeToolGateway tools = FakeToolGateway.thatFailsEveryCall();

        new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertFalse(lastToolResult(model, 2).endsWith(ToolUseLoopService.REPEATED_FAILURE_NOTE), "a first failure is passed through as is");
        String second = lastToolResult(model, 3);
        assertTrue(second.startsWith("The search_concepts lookup failed"), "the original error is kept: " + second);
        assertTrue(second.endsWith(ToolUseLoopService.REPEATED_FAILURE_NOTE), "different arguments, same error: " + second);
    }

    @Test
    void aToolFailingTheSameWayThreeTimesStopsToolUseAndAsksForAnAnswer() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{\"search\":\"sex\"}"));
        FakeToolGateway tools = FakeToolGateway.thatFailsEveryCall();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(ToolUseLoopService.MAX_REPEATED_FAILURES, tools.calls.size(), "no call runs after the stop, well short of the cap");
        assertEquals(ToolUseLoopService.REPEATED_FAILURE_STOP_RESULT, lastToolResult(model, 4));
        assertEquals(ToolUseLoopService.CAP_REACHED_RESULT, lastToolResult(model, 5), "a request after the stop is answered, not run");
        assertEquals(5, model.callCount, "the stop takes the cap's path: one last call to answer");
        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void aCallInTheSameBatchAfterTheStopIsNotRun() {
        ModelTurnResult twoCalls = new ModelTurnResult(
            false, null, List.of(new RequestedToolCall("a", "search_concepts", "{}"), new RequestedToolCall("b", "list_facets", "{}")), 10, 5
        );
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{}"), toolCallTurn("search_concepts", "{}"), twoCalls, doneTurn("Answered")
        );
        FakeToolGateway tools = FakeToolGateway.thatFailsEveryCall();

        ChatResponse response = new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(3, tools.calls.size(), "list_facets, after the third failure in its batch, never reaches the gateway");
        assertEquals(ToolUseLoopService.CAP_REACHED_RESULT, lastToolResult(model, 4));
        assertEquals("Answered", response.response());
    }

    @Test
    void failuresWithDifferentErrorsAreNotCountedTogether() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("get_concept", "{\"dataset\":\"x\",\"conceptPath\":\"\\\\a\\\\\"}"),
            toolCallTurn("get_concept", "{\"dataset\":\"x\",\"conceptPath\":\"\\\\b\\\\\"}"),
            toolCallTurn("get_concept", "{\"dataset\":\"x\",\"conceptPath\":\"\\\\c\\\\\"}"), doneTurn("Answered")
        );

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Answered", response.response());
        String third = lastToolResult(model, 4);
        assertTrue(third.startsWith("Unknown concept path"), "each invented path is its own error: " + third);
        assertFalse(third.endsWith(ToolUseLoopService.REPEATED_FAILURE_NOTE));
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

    @Test
    void aToolListingFailureThrowsAControlledUnavailableError() {
        FakeModelClient model = FakeModelClient.returning(doneTurn("unused"));
        FakeToolGateway tools = FakeToolGateway.thatFailsListing();

        PicsureException thrown =
            assertThrows(PicsureException.class, () -> new ToolUseLoopService(model, tools, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER));

        assertEquals("tools_unavailable", thrown.getErrorType());
        assertEquals(0, model.callCount, "the model is never called without a tool list");
    }

    @Test
    void aModelThatEndsItsTurnWithNoTextGetsTheFallbackNotANullReply() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn(null));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void aModelThatEndsItsTurnEmptyIsAskedAgainAndTheSecondReplyIsUsed() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn(null), doneTurn("Found it"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Found it", response.response());
        assertEquals(3, model.callCount);
        assertEquals(model.historySeenOnCall(2), model.historySeenOnCall(3), "the retry resends the conversation unchanged");
    }

    @Test
    void aModelThatKeepsEndingEmptyIsRetriedTwiceThenGetsTheFallback() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn(null));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
        assertEquals(4, model.callCount, "the tool-call turn, the empty turn, and two retries");
    }

    @Test
    void anEmptyTurnIsNotRetriedOnceAProposalWasAccepted() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn(null)
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(3, model.callCount);
        assertEquals(ReplyText.PROPOSAL_ATTACHED_TEXT, response.response());
    }

    @Test
    void aFollowUpProposalThatSendsOnlyFiltersKeepsTheCurrentSelect() throws Exception {
        String current = "{\"select\":[\"\\\\demo\\\\bmi\\\\\"]}";
        ChatRequest request = new ChatRequest("narrow", "c", "r", new ObjectMapper().readTree(current), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals("\\demo\\bmi\\", response.query().path("select").path(0).asText());
        assertEquals(AGE_PATH, response.query().path("phenotypicClause").path("conceptPath").asText());
    }

    @Test
    void aFilteredPathTheModelLeftOutOfSelectIsAddedBack() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(1, response.query().path("select").size());
        assertEquals(AGE_PATH, response.query().path("select").path(0).asText());
    }

    @Test
    void aFilteredPathAlreadyInSelectIsNotRepeated() {
        String withSelect = "{\"select\":[\"demo\\\\age\"]," + AGE_QUERY.substring(1);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + withSelect.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(1, response.query().path("select").size());
    }

    @Test
    void aSelectOnlyQueryWithNoFiltersToKeepIsReadAsTheSearchThatFoundTheVariables() {
        // Gpt-oss wrote a lookup as a select-only query in nearly every run, then retried after the refusal.
        String selectOnly = "{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("propose_query", selectOnly), doneTurn("The age variable is demo age.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query(), "a select-only proposal is not surfaced as a query");
        assertEquals("age", response.search());
        ConversationEntry result = model.historySeenOnCall(3).get(model.historySeenOnCall(3).size() - 1);
        assertTrue(((ConversationEntry.ToolResultEntry) result).content().contains("received"), "the model is not sent a refusal");
    }

    @Test
    void aSelectOnlyQueryAlongsideASearchKeepsTheSearchAndDropsTheQuery() {
        String both = "{\"search\":\"age\",\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("propose_query", both), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query());
        assertEquals("age", response.search());
    }

    @Test
    void aQueryWithNoFiltersIsRefusedWhenNoSearchFoundAnythingAndNothingHasFilters() {
        String selectOnly = "{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("get_concept", "{}"), toolCallTurn("propose_query", selectOnly), doneTurn("Nothing matched.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query(), "a select-only proposal is not surfaced");
        assertNull(response.search());
        ConversationEntry refusal = model.historySeenOnCall(3).get(model.historySeenOnCall(3).size() - 1);
        assertTrue(((ConversationEntry.ToolResultEntry) refusal).content().contains("no filters"));
    }

    @Test
    void aReplyThatSaysTheTableNowShowsVariablesGetsTheSearchThatFoundThemWhenNoneWasProposed() {
        // "now displayed in your results table" with no propose_query call.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), doneTurn("These variables are now displayed in your results table.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
    }

    @Test
    void aReplyThatPointsToTheTableOfResultsCountsAsAScreenClaim() {
        // "You can view this variable in the table of results." with no propose_query call.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), doneTurn("You can view this variable in the table of results.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
    }

    @Test
    void aFacetNarrowedSearchTheModelNeverProposedIsAttachedWithoutAScreenClaim() {
        // List_facets and the narrowed search_concepts ran, the reply was right, but propose_query was skipped.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("list_facets", "{}"), toolCallTurn("search_concepts", NARROWED_SEARCH), doneTurn("Here are the age variables in demo.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("age", response.search());
        assertEquals(List.of(new FacetSelection("study", "demo")), response.facets());
    }

    @Test
    void aReplyWithoutAScreenClaimGetsNoSearchItDidNotPropose() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), doneTurn("Age is available as demo age.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.search());
    }

    @Test
    void anEmptyProposalIsRefusedInsteadOfAcknowledged() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("propose_query", "{}"), doneTurn("Done"));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query());
        ConversationEntry refusal = model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(((ConversationEntry.ToolResultEntry) refusal).content().contains("empty"));
    }

    @Test
    void aSelectOnlyProposalKeepsTheFiltersTheResearchersQueryAlreadyHas() throws Exception {
        // "Select all race variables" with filters on screen came back with the filters dropped.
        ChatRequest request = new ChatRequest("select race", "c", "r", new ObjectMapper().readTree(AGE_QUERY), null, null);
        String raceResult = "{\"concepts\":[{\"conceptPath\":\"\\\\demo\\\\race\\\\\"}]}";
        String selectOnly = "{\"query\":{\"select\":[\"\\\\demo\\\\race\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"), toolCallTurn("propose_query", selectOnly), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(raceResult), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals("\\demo\\race\\", response.query().path("select").path(0).asText());
        assertEquals("REQUIRED", response.query().path("phenotypicClause").path("phenotypicFilterType").asText());
    }

    @Test
    void aSelectOnlyProposalAddsToTheVariablesTheResearcherAlreadySelected() throws Exception {
        // "Select all race variables" came back with the race paths alone, and BMI dropped out of the researcher's columns.
        String current = "{\"select\":[\"\\\\demo\\\\bmi\\\\\"]," + AGE_QUERY.substring(1);
        ChatRequest request = new ChatRequest("Select all race variables", "c", "r", new ObjectMapper().readTree(current), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
            toolCallTurn("propose_query", "{\"query\":{\"select\":[\"\\\\demo\\\\race\\\\\"]}}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        List<String> select = new ArrayList<>();
        response.query().path("select").forEach(path -> select.add(path.asText()));
        assertEquals(List.of("\\demo\\bmi\\", "\\demo\\race\\", AGE_PATH), select);
    }

    @Test
    void aSelectOrAddSelectOnlyAddsToTheResearchersVariablesWhateverTheMessageSays() throws Exception {
        // Asked for the whole select, gpt-oss sent only what it was adding; a variable goes only when named in removeSelect.
        String current = "{\"select\":[\"\\\\demo\\\\bmi\\\\\"]," + AGE_QUERY.substring(1);
        for (String field : List.of("select", "addSelect")) {
            for (String message : List.of("Replace BMI with race", "Add race, only for adults")) {
                ChatRequest request = new ChatRequest(message, "c", "r", new ObjectMapper().readTree(current), null, null);
                FakeModelClient model = FakeModelClient.returning(
                    toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
                    toolCallTurn("propose_query", "{\"query\":{\"" + field + "\":[\"\\\\demo\\\\race\\\\\"]}}"), doneTurn("Done")
                );

                ChatResponse response =
                    new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

                assertEquals(List.of("\\demo\\bmi\\", "\\demo\\race\\", AGE_PATH), selected(response), field + ": " + message);
            }
        }
    }

    @Test
    void removeSelectTakesAVariableOutAndTheModelIsToldWhatIsSelectedNow() throws Exception {
        String current = "{\"select\":[\"\\\\demo\\\\bmi\\\\\"]," + AGE_QUERY.substring(1);
        ChatRequest request = new ChatRequest("Replace BMI with race", "c", "r", new ObjectMapper().readTree(current), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
            toolCallTurn(
                "propose_query", "{\"query\":{\"addSelect\":[\"\\\\demo\\\\race\\\\\"],\"removeSelect\":[\"\\\\demo\\\\bmi\\\\\"]}}"
            ),
            doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals(List.of("\\demo\\race\\", AGE_PATH), selected(response));
        assertEquals("REQUIRED", response.query().path("phenotypicClause").path("phenotypicFilterType").asText());
        String result = lastToolResult(model, 3);
        assertTrue(result.contains("Their selected variables are now: race, age."), result);
    }

    @Test
    void removingEveryVariableSendsAnEmptySelectRatherThanLeavingItOut() throws Exception {
        // The response leaves a null select out, and the UI would keep the old one.
        ChatRequest request =
            new ChatRequest("Remove BMI", "c", "r", new ObjectMapper().readTree("{\"select\":[\"\\\\demo\\\\bmi\\\\\"]}"), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("propose_query", "{\"query\":{\"removeSelect\":[\"\\\\demo\\\\bmi\\\\\"]}}"), doneTurn("Done")
        );

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertTrue(response.query().path("select").isArray(), response.query().toString());
        assertEquals(List.of(), selected(response));
    }

    @Test
    void aRemovalThatCannotHappenIsSaidSoInsteadOfLeftForTheModelToClaim() throws Exception {
        // AGE is filtered on, so the table keeps showing it; race was never selected.
        String current = "{\"select\":[\"\\\\demo\\\\bmi\\\\\"]," + AGE_QUERY.substring(1);
        ChatRequest request = new ChatRequest("Remove age and race", "c", "r", new ObjectMapper().readTree(current), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
            toolCallTurn("propose_query", "{\"query\":{\"removeSelect\":[\"\\\\demo\\\\age\\\\\",\"\\\\demo\\\\race\\\\\"]}}"),
            doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertNull(response.query(), "nothing changed");
        String result = lastToolResult(model, 3);
        assertTrue(result.contains("age stayed selected, because the query filters on it"), result);
        assertTrue(result.contains("race was not selected"), result);
    }

    @Test
    void aRemovedPathNoToolOrQueryShowedIsRefused() throws Exception {
        String current = "{\"select\":[\"\\\\demo\\\\bmi\\\\\"]," + AGE_QUERY.substring(1);
        ChatRequest request = new ChatRequest("Remove height", "c", "r", new ObjectMapper().readTree(current), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("propose_query", "{\"query\":{\"removeSelect\":[\"\\\\demo\\\\height\\\\\"]}}"), doneTurn("Done")
        );

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertNull(response.query());
        assertTrue(lastToolResult(model, 2).contains("Unknown concept path"), lastToolResult(model, 2));
    }

    @Test
    void addingToAResearchersVariablesWithNoFiltersIsAQueryNotALookup() throws Exception {
        // Read as a lookup, the race variables would have been shown as a search and nothing added to the selected BMI.
        ChatRequest request =
            new ChatRequest("Add race", "c", "r", new ObjectMapper().readTree("{\"select\":[\"\\\\demo\\\\bmi\\\\\"]}"), null, null);
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
            toolCallTurn("propose_query", "{\"query\":{\"addSelect\":[\"\\\\demo\\\\race\\\\\"]}}"), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals(List.of("\\demo\\bmi\\", "\\demo\\race\\"), selected(response));
        assertNull(response.search());
    }

    /** The researcher's query selecting {@code count} variables, each {@code \\demo\\v<i>\\}. */
    private static ChatRequest requestSelecting(int count) throws Exception {
        String paths = java.util.stream.IntStream.range(0, count).mapToObj(i -> "\"\\\\demo\\\\v" + i + "\\\\\"")
            .collect(java.util.stream.Collectors.joining(","));
        return new ChatRequest("Add race", "c", "r", new ObjectMapper().readTree("{\"select\":[" + paths + "]}"), null, null);
    }

    private static ChatResponse addRace(ChatRequest request, String query, FakeModelClient[] modelOut) {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"), toolCallTurn("propose_query", query), doneTurn("Done")
        );
        modelOut[0] = model;
        return new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);
    }

    @Test
    void additionsThatPushTheMergedSelectionOverTheLimitAreRefused() throws Exception {
        // 100 selected is allowed, so the researcher's query is shown; the one added variable makes 101.
        FakeModelClient[] model = new FakeModelClient[1];

        ChatResponse response = addRace(requestSelecting(ProposeQueryInput.MAX_SELECT), "{\"query\":{\"addSelect\":[\"\\\\demo\\\\race\\\\\"]}}", model);

        assertNull(response.query());
        assertTrue(lastToolResult(model[0], 3).contains("too large"), lastToolResult(model[0], 3));
    }

    @Test
    void additionsThatLeaveTheMergedSelectionAtTheLimitAreAccepted() throws Exception {
        FakeModelClient[] model = new FakeModelClient[1];

        ChatResponse response =
            addRace(requestSelecting(ProposeQueryInput.MAX_SELECT - 1), "{\"query\":{\"addSelect\":[\"\\\\demo\\\\race\\\\\"]}}", model);

        assertEquals(ProposeQueryInput.MAX_SELECT, selected(response).size());
        assertTrue(selected(response).contains("\\demo\\race\\"), selected(response).toString());
    }

    @Test
    void aRemovalMakesRoomForAnAdditionAtTheLimit() throws Exception {
        FakeModelClient[] model = new FakeModelClient[1];
        String swap = "{\"query\":{\"addSelect\":[\"\\\\demo\\\\race\\\\\"],\"removeSelect\":[\"\\\\demo\\\\v0\\\\\"]}}";

        ChatResponse response = addRace(requestSelecting(ProposeQueryInput.MAX_SELECT), swap, model);

        assertEquals(ProposeQueryInput.MAX_SELECT, selected(response).size());
        assertTrue(selected(response).contains("\\demo\\race\\"), selected(response).toString());
        assertFalse(selected(response).contains("\\demo\\v0\\"), selected(response).toString());
    }

    private static List<String> selected(ChatResponse response) {
        List<String> select = new ArrayList<>();
        response.query().path("select").forEach(path -> select.add(path.asText()));
        return select;
    }

    @Test
    void anAcceptedLookupTellsTheModelNothingWasSelected() {
        // Told only {"received":true}, the model said the variables were "now selected" when they were shown as a search.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"),
            toolCallTurn("propose_query", "{\"query\":{\"select\":[\"\\\\demo\\\\race\\\\\"]}}"), doneTurn("Done")
        );

        new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        String result = lastToolResult(model, 3);
        assertTrue(result.contains("matching the search \\\"race\\\""), result);
        assertTrue(result.contains("nothing was added"), result);
    }

    @Test
    void aReplyPathMadeFromAValueIsSentBackNamingTheVariableThatHoldsIt() {
        // The value "G47.9 Sleep disorder, unspecified" of the G47 folder was written as a path of its own.
        String invented = "Sleep: `\\demo\\G47.9 Sleep disorder, unspecified\\`";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"sleep\"}"), doneTurn(invented), doneTurn("Sleep: `\\demo\\G47 Sleep disorders\\`")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(SLEEP_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("Sleep: `\\demo\\G47 Sleep disorders\\`", response.response());
        List<ConversationEntry> history = model.historySeenOnCall(3);
        assertEquals(invented, ((ConversationEntry.AssistantToolCallEntry) history.get(history.size() - 2)).text());
        String repair = ((ConversationEntry.UserEntry) history.get(history.size() - 1)).text();
        assertTrue(repair.contains("\"G47.9 Sleep disorder, unspecified\" is not a variable"), repair);
        assertTrue(repair.contains("`\\demo\\G47 Sleep disorders\\`"), repair);
    }

    @Test
    void aReplyPathIsCorrectedAtMostOnceAndAnUnreturnedParentIsNamedAsOne() {
        String folder = "Sleep: `\\demo\\`";
        FakeModelClient model =
            FakeModelClient.returning(toolCallTurn("search_concepts", "{\"search\":\"sleep\"}"), doneTurn(folder), doneTurn(folder));

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(SLEEP_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(folder, response.response(), "a reply still wrong after one correction goes out as written");
        assertEquals(3, model.callCount);
        List<ConversationEntry> history = model.historySeenOnCall(3);
        assertTrue(((ConversationEntry.UserEntry) history.get(history.size() - 1)).text().contains("is a parent of paths the results returned"));
    }

    @Test
    void aCountedQueryIsProposedWhenTheReplySaysTheQueryChangedButNothingWasProposed() {
        // Counted the cohort, said "I included the BMI variable in the query", never called propose_query.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("count_participants", "{\"query\":" + AGE_QUERY + "}"),
            doneTurn("About 3,102 participants. I included the age variable in the query.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(AGE_PATH, response.query().path("phenotypicClause").path("conceptPath").asText());
    }

    @Test
    void aCountedQueryIsNotProposedWhenTheReplyOnlyOffersToChangeTheQuery() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("count_participants", "{\"query\":" + AGE_QUERY + "}"),
            doneTurn("About 3,102 participants. Let me know if you'd like me to add this filter to your query.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query());
    }

    @Test
    void aCountedQueryIsNotProposedWhenTheReplyOnlyReportsTheCount() {
        // A what-if count; "in your cohort" describes the count, it doesn't claim the query changed.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("count_participants", "{\"query\":" + AGE_QUERY + "}"),
            doneTurn("About 3,102 participants in your cohort have an age recorded. Your cohort includes 9,000 participants in total.")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query());
    }

    @Test
    void theOneSearchThatFoundTheNamedVariablesIsProposedWhenNothingWasProposed() {
        // One combined search, a reply listing some of its paths, and no propose_query.
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"), doneTurn("Race: `\\demo\\race\\`")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("race", response.search());
    }

    @Test
    void noSearchIsProposedWhenSeveralSearchesFoundTheNamedVariables() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"race\"}"), toolCallTurn("search_concepts", "{\"search\":\"ethnicity\"}"),
            doneTurn("Race: `\\demo\\race\\`")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(RACE_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.search(), "which search to show is ambiguous, so none is chosen");
    }

    @Test
    void aSelectOnlyProposalKeepsTheFiltersOfAnEarlierProposalInTheSameTurn() {
        String selectOnly = "{\"query\":{\"select\":[\"\\\\demo\\\\age\\\\\"]}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY + "}"),
            toolCallTurn("propose_query", selectOnly), doneTurn("Done")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("REQUIRED", response.query().path("phenotypicClause").path("phenotypicFilterType").asText());
    }

    @Test
    void aReplyThatScrubsDownToNothingGetsTheFallbackWhenNoProposalIsAttached() {
        FakeModelClient model = FakeModelClient.returning(doneTurn("query: {\"select\":[\"a\"]} facets: [] search: \"\""));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void aProposalWrittenAsTheReplyIsCheckedAndSurfacedLikeAToolCall() throws Exception {
        // The model wrote the narrowed query as its reply instead of calling propose_query.
        ChatRequest request = new ChatRequest("narrow it", "c", "r", new ObjectMapper().readTree(AGE_QUERY), null, null);
        String narrowed = "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"min\":50}}}";
        FakeModelClient model = FakeModelClient.returning(doneTurn(narrowed));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(request, CALLER);

        assertEquals(ReplyText.PROPOSAL_ATTACHED_TEXT, response.response());
        assertEquals(50, response.query().path("phenotypicClause").path("min").asInt());
        assertEquals(1, model.callCount);
    }

    @Test
    void aProposalWrittenAsTheReplyThatFailsACheckIsNotSurfaced() {
        String invented = "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\made\\\\up\\\\\"}}}";
        FakeModelClient model = FakeModelClient.returning(doneTurn(invented));

        ChatResponse response = new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertNull(response.query());
        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void aReplyRepeatingAnAlreadyAcceptedProposalDoesNotReplaceIt() {
        String other = "{\"query\":{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"min\":50}}}";
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"), toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY + "}"), doneTurn(other)
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("REQUIRED", response.query().path("phenotypicClause").path("phenotypicFilterType").asText());
    }

    @Test
    void hittingTheCapAfterAnAcceptedProposalSaysSoAndKeepsTheQuery() {
        FakeModelClient model = FakeModelClient.returning(
            toolCallTurn("search_concepts", "{\"search\":\"age\"}"),
            toolCallTurn("propose_query", "{\"query\":" + AGE_QUERY.replace("\\\\demo\\\\age\\\\", "demo\\\\age") + "}"),
            toolCallTurn("search_concepts", "{\"search\":\"age\"}")
        );

        ChatResponse response =
            new ToolUseLoopService(model, FakeToolGateway.returning(DICTIONARY_RESULT), PROPOSE_QUERY_TOOL, 3).handle(REQUEST, CALLER);

        assertEquals(ReplyText.PROPOSAL_ATTACHED_TEXT, response.response());
        assertEquals(AGE_PATH, response.query().path("phenotypicClause").path("conceptPath").asText());
    }

    @Test
    void theConversationAndRequestIdsTagLogLinesDuringTheTurnAndAreClearedAfterwards() {
        String[] seenDuringTool = new String[2];
        McpToolGateway gateway = new McpToolGateway() {
            @Override
            public List<ToolDefinition> listTools(CallerContext caller) {
                return List.of();
            }

            @Override
            public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
                seenDuringTool[0] = MDC.get("conversationId");
                seenDuringTool[1] = MDC.get("chatRequestId");
                return ToolResult.success("{}");
            }
        };
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn("ok"));

        new ToolUseLoopService(model, gateway, PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER);

        assertEquals("conv-1", seenDuringTool[0]);
        assertEquals("req-1", seenDuringTool[1]);
        assertNull(MDC.get("conversationId"));
        assertNull(MDC.get("chatRequestId"));
    }

    @Test
    void theLogIdsAreClearedEvenWhenTheModelFails() {
        FakeModelClient model = FakeModelClient.throwingImmediately();

        assertThrows(PicsureException.class, () -> new ToolUseLoopService(model, new FakeToolGateway(), PROPOSE_QUERY_TOOL, 8).handle(REQUEST, CALLER));

        assertNull(MDC.get("conversationId"));
        assertNull(MDC.get("chatRequestId"));
    }

    private static String lastToolResult(FakeModelClient model, int callNumber) {
        List<ConversationEntry> history = model.historySeenOnCall(callNumber);
        return ((ConversationEntry.ToolResultEntry) history.get(history.size() - 1)).content();
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
        private final boolean failListing;
        private final String successContent;

        FakeToolGateway() {
            this(false, false, "{}");
        }

        private FakeToolGateway(boolean failEveryCall, boolean failListing, String successContent) {
            this.failEveryCall = failEveryCall;
            this.failListing = failListing;
            this.successContent = successContent;
        }

        static FakeToolGateway thatFailsEveryCall() {
            return new FakeToolGateway(true, false, "{}");
        }

        /** A gateway whose {@code tools/list} fails, as when the gateway is down before the turn starts. */
        static FakeToolGateway thatFailsListing() {
            return new FakeToolGateway(false, true, "{}");
        }

        /** A gateway whose every successful call returns the given content, e.g. a search result carrying concept paths. */
        static FakeToolGateway returning(String successContent) {
            return new FakeToolGateway(false, false, successContent);
        }

        @Override
        public List<ToolDefinition> listTools(CallerContext caller) {
            if (failListing) {
                throw new RestClientException("mock gateway failure");
            }
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
