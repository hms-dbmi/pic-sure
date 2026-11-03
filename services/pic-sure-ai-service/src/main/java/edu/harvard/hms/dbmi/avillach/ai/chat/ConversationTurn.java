package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One finished chat turn, as kept for the rest of its conversation. Only the final reply is kept, never one the service had the model
 * rewrite or a correction it sent as a user message: replaying those would put invented paths, or words the researcher never wrote, back in
 * front of the model.
 *
 * @param message the researcher's message as sent, without the screen state appended to it: the next request carries the screen as it is
 *        then, and that is what counts
 * @param reply the reply the researcher was sent
 * @param toolResults every successful dictionary lookup this turn, in order. Never shown to the model again; they rebuild what the guards
 *        have seen (see {@link EarlierTurns})
 * @param sentQuery the query the response sent the researcher, or null if it sent none. What the service proposed, not what is on their
 *        screen, which every request still carries: it is kept only to tell the model what the researcher has since removed by hand
 */
public record ConversationTurn(String message, String reply, List<ToolOutput> toolResults, JsonNode sentQuery) {

    public ConversationTurn {
        toolResults = List.copyOf(toolResults);
    }

    /**
     * @param toolName the tool that ran
     * @param argumentsJson the arguments it was called with
     * @param content its result, after the service's own additions (such as the empty {@code list_facets} note)
     */
    public record ToolOutput(String toolName, String argumentsJson, String content) {
    }

    /** @return roughly how much memory the turn holds, in characters */
    int size() {
        int size = length(message) + length(reply) + (sentQuery == null ? 0 : sentQuery.toString().length());
        for (ToolOutput output : toolResults) {
            size += length(output.toolName()) + length(output.argumentsJson()) + length(output.content());
        }
        return size;
    }

    private static int length(String text) {
        return Objects.requireNonNullElse(text, "").length();
    }
}
