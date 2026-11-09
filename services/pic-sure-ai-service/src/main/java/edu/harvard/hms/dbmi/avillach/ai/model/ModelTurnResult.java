package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.List;

/**
 * One {@link ConverseModelClient#send} call's outcome: either the model is done ({@code done=true}, {@code text} is the final answer) or it
 * wants one or more tools called before it can continue ({@code done=false}, {@code toolCalls} non-empty).
 *
 * @param done whether the model reached {@code end_turn} (no further tool calls)
 * @param text the model's text for this turn -- the final answer when {@code done}, or any text accompanying a tool-call request otherwise
 * @param toolCalls the tool calls requested this turn, empty when {@code done}
 * @param inputTokens prompt tokens for this call, for audit/cost telemetry (free from the Converse response)
 * @param outputTokens completion tokens for this call
 */
public record ModelTurnResult(boolean done, String text, List<RequestedToolCall> toolCalls, int inputTokens, int outputTokens) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
