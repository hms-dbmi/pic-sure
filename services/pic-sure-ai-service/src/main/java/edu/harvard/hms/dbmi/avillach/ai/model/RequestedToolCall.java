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
}
