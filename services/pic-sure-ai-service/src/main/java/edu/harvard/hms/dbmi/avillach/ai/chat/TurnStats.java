package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.List;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;
import edu.harvard.hms.dbmi.avillach.ai.model.ModelTurnResult;

/** Running totals for one chat turn, for its one summary log line -- written whether the turn answers or fails. */
final class TurnStats {

    private final long startNanos = System.nanoTime();

    int modelCalls;
    int inputTokens;
    int outputTokens;
    int toolErrors;
    /** Each tool call in order as {@code name(N chars)}, which makes an oversized result easy to spot. */
    final List<String> toolsCalled = new ArrayList<>();
    /** Each time the loop stepped in for model misbehavior (e.g. {@code emptyReplyRetry}, {@code pathRepair}): a model-quality signal. */
    final List<String> corrections = new ArrayList<>();

    void addModelCall(ModelTurnResult result) {
        modelCalls++;
        inputTokens += result.inputTokens();
        outputTokens += result.outputTokens();
    }

    void addToolCall(String name, ToolResult result) {
        int chars = result.content() == null ? 0 : result.content().length();
        toolsCalled.add(name + "(" + chars + " chars)");
        if (result.error()) {
            toolErrors++;
        }
    }

    long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Input tokens are the billed total: each model call resends the whole history. */
    String summary() {
        return "durationMs=" + elapsedMs() + " modelCalls=" + modelCalls + " inputTokens=" + inputTokens + " outputTokens=" + outputTokens
            + " toolCalls=" + toolsCalled.size() + " toolErrors=" + toolErrors + " tools=[" + String.join(", ", toolsCalled) + "]"
            + " corrections=[" + String.join(", ", corrections) + "]";
    }
}
