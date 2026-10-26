package edu.harvard.hms.dbmi.avillach.ai.mcp;

/**
 * The outcome of one {@link McpToolGateway#callTool} call, fed back to the model as a normal tool result either way -- a failed lookup is
 * content the model reasons about ("I couldn't search the dictionary right now"), never an exception that reaches the HTTP layer. See
 * {@code PLAN.md}'s "Timeouts & partial results" section.
 *
 * @param content the result payload (or error message) as a JSON or plain-text string, exactly as it should appear in the tool result sent
 *        back to the model
 * @param error whether the call failed, so the dispatch loop can log/count it separately from a normal result without needing to parse
 *        {@code content}
 */
public record ToolResult(String content, boolean error) {

    /**
     * A successful result.
     *
     * @param content the result payload
     * @return a non-error {@link ToolResult}
     */
    public static ToolResult success(String content) {
        return new ToolResult(content, false);
    }

    /**
     * A failed call. {@code content} is still model-facing text (what the model sees in the tool result), not an exception message.
     *
     * @param content the model-facing error description
     * @return an error {@link ToolResult}
     */
    public static ToolResult failure(String content) {
        return new ToolResult(content, true);
    }
}
