package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolUseLoopServiceTest {

    private static final ChatRequest REQUEST = new ChatRequest("hi", "conv-1", "req-1", null, null, null);
    private static final CallerContext CALLER = new CallerContext("Bearer test-jwt", "req-1", "user-1");

    @Test
    void returnsTextOnlyResponseWhenModelEndsTurnImmediately() {
        FakeModelClient model = FakeModelClient.returning(doneTurn("Hello there"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, 8).handle(REQUEST, CALLER);

        assertEquals("Hello there", response.response());
        assertNull(response.query());
        assertNull(response.facets());
        assertNull(response.search());
        assertTrue(tools.calls.isEmpty(), "no tool should be called when the model never asks for one");
        assertEquals(1, model.callCount);
    }

    @Test
    void dispatchesEveryToolCallToTheMcpGatewayIncludingTheProposalTool() {
        FakeModelClient model = FakeModelClient
            .returning(toolCallTurn("search_concepts", "{\"query\":\"bmi\"}"), toolCallTurn("propose_query", "{}"), doneTurn("Done"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, 8).handle(REQUEST, CALLER);

        assertEquals("Done", response.response());
        assertEquals(List.of("search_concepts", "propose_query"), tools.calls.stream().map(c -> c.name).toList());
        assertEquals(CALLER, tools.calls.get(0).caller);
        // A proposal result is dispatched and fed back, but never promoted to the structured fields yet.
        assertNull(response.query());
    }

    @Test
    void stopsAtTheIterationCapAndReturnsTheBestAnswerSoFar() {
        FakeModelClient model = FakeModelClient
            .returning(toolCallTurn("search_concepts", "{}"), toolCallTurn("search_concepts", "{}"), toolCallTurn("search_concepts", "{}"));
        FakeToolGateway tools = new FakeToolGateway();

        ChatResponse response = new ToolUseLoopService(model, tools, 3).handle(REQUEST, CALLER);

        assertEquals(3, model.callCount, "the cap bounds model calls, not just tool calls");
        assertEquals(2, tools.calls.size(), "only two rounds are dispatched before the third call hits the cap");
        assertEquals(ToolUseLoopService.FALLBACK_TEXT, response.response());
    }

    @Test
    void aToolFailureIsFedBackAsAnErrorResultAndNeverThrows() {
        FakeModelClient model = FakeModelClient.returning(toolCallTurn("search_concepts", "{}"), doneTurn("Handled the error"));
        FakeToolGateway tools = FakeToolGateway.thatFailsEveryCall();

        ChatResponse response = new ToolUseLoopService(model, tools, 8).handle(REQUEST, CALLER);

        assertEquals("Handled the error", response.response());
        ConversationEntry lastEntryOfFirstReplay = model.historySeenOnCall(2).get(model.historySeenOnCall(2).size() - 1);
        assertTrue(lastEntryOfFirstReplay instanceof ConversationEntry.ToolResultEntry, "the failure must still appear as a tool result");
    }

    @Test
    void aModelFailureThrowsADistinctErrorFromAToolFailure() {
        FakeModelClient model = FakeModelClient.throwingImmediately();
        FakeToolGateway tools = new FakeToolGateway();

        PicsureException thrown =
            assertThrows(PicsureException.class, () -> new ToolUseLoopService(model, tools, 8).handle(REQUEST, CALLER));

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

        FakeToolGateway() {
            this(false);
        }

        private FakeToolGateway(boolean failEveryCall) {
            this.failEveryCall = failEveryCall;
        }

        static FakeToolGateway thatFailsEveryCall() {
            return new FakeToolGateway(true);
        }

        @Override
        public List<ToolDefinition> listTools() {
            return List.of();
        }

        @Override
        public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
            calls.add(new Call(name, argumentsJson, caller));
            if (failEveryCall) {
                throw new RuntimeException("mock gateway failure");
            }
            return ToolResult.success("{}");
        }
    }
}
