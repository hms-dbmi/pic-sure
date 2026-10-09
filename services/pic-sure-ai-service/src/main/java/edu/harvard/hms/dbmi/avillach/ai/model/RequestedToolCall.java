package edu.harvard.hms.dbmi.avillach.ai.model;

/**
 * One tool call the model requested in a turn, in the shape the dispatch loop needs to route it (see
 * {@code edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway#callTool}) and later echo back as a {@link ConversationEntry.ToolResultEntry}.
 *
 * @param id the model-assigned id for this call, echoed back on the matching tool result
 * @param name the tool name
 * @param argumentsJson the call's arguments, as the raw JSON string the model produced
 */
public record RequestedToolCall(String id, String name, String argumentsJson) {

    /**
     * gpt-oss sometimes leaks its harmony channel marker into the tool name (e.g. {@code search_concepts<|channel|>commentary}). Left
     * as-is the name matches no tool, and echoing it back in history makes Bedrock reject the whole request (names must match
     * {@code [a-zA-Z0-9_-]+}). Truncate at the first character outside that set to recover the real name.
     */
    public RequestedToolCall {
        if (name != null) {
            int end = 0;
            while (end < name.length() && isNameChar(name.charAt(end))) {
                end++;
            }
            name = name.substring(0, end);
        }
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }
}
