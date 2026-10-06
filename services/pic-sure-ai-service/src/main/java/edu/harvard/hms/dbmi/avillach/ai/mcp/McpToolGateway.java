package edu.harvard.hms.dbmi.avillach.ai.mcp;

import java.util.List;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;

/**
 * Everything the dispatch loop needs from "the other thing besides Bedrock this service talks to." Every tool call the model requests --
 * including the query/facets/search proposal tool -- goes through this interface; {@code ai-service} has no locally-handled tool of its own
 * (see {@code PLAN.md}'s Request-lifecycle note and the Stage-2 plan: the proposal tool is assumed to live on the MCP server too, so the
 * service only ever calls Bedrock or this gateway).
 *
 * <p>Two implementations: {@link edu.harvard.hms.dbmi.avillach.ai.mcp.mock.MockMcpToolGateway} (this stage,
 * {@code picsure.ai.mcp.mode=mock}) and, later, a real MCP client against {@code pic-sure-mcp} via the gateway's {@code /mcp} route
 * ({@code picsure.ai.mcp.mode=gateway}). Swapping one for the other is a bean choice, not an API change for the rest of the service.
 */
public interface McpToolGateway {

    /**
     * The tools available to advertise to the model this turn.
     *
     * @return the tool definitions
     */
    List<ToolDefinition> listTools();

    /**
     * Calls one tool by name, replaying the caller's identity exactly as a real MCP call would.
     *
     * @param name the tool name, as requested by the model
     * @param argumentsJson the tool call's arguments, as the raw JSON string the model produced
     * @param caller the caller's identity; {@link CallerContext#authorization()} is replayed untouched on the real implementation's
     *        outbound gateway call
     * @return the tool result -- never throws for an ordinary lookup failure, see {@link ToolResult}
     */
    ToolResult callTool(String name, String argumentsJson, CallerContext caller);
}
