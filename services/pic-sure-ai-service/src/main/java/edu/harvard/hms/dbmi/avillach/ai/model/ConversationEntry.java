package edu.harvard.hms.dbmi.avillach.ai.model;

import java.util.List;

/**
 * One turn in the running conversation {@link ConverseModelClient#send} resends in full each call -- the Converse API is stateless. Plain
 * local types so the dispatch loop (in {@code ai.chat}) never needs to construct a Spring AI {@code Message}; only {@code ai.model}'s
 * implementation translates these.
 */
public sealed interface ConversationEntry {

    /** The researcher's message for this turn. */
    record UserEntry(String text) implements ConversationEntry {
    }

    /**
     * The model's own turn when it requested one or more tools, so it can be replayed back to the model alongside the
     * {@link ToolResultEntry} answers that follow it.
     *
     * @param text any text the model produced alongside the tool calls, or null
     * @param toolCalls the tool calls the model requested
     */
    record AssistantToolCallEntry(String text, List<RequestedToolCall> toolCalls) implements ConversationEntry {
    }

    /**
     * One tool's result, fed back to the model for the {@link RequestedToolCall} it answers.
     *
     * @param toolCallId the id the model's request carried, so the model can match the answer to the call
     * @param toolName the tool name the result is for
     * @param content the result content -- see {@code ToolResult}
     */
    record ToolResultEntry(String toolCallId, String toolName, String content) implements ConversationEntry {
    }
}
