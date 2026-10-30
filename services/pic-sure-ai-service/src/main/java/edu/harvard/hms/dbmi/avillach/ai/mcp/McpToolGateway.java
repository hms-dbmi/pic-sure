package edu.harvard.hms.dbmi.avillach.ai.mcp;

import java.util.List;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;

/**
 * Everything the dispatch loop needs from "the other thing besides Bedrock this service talks to." Every data-access tool call the model
 * requests -- the dictionary/count lookups -- goes through this interface. The one exception is the query/facets/search proposal tool
 * ({@code edu.harvard.hms.dbmi.avillach.ai.chat.ProposeQueryTool}), which is handled locally by the tool-use loop instead, since it is not
 * a data-access call at all (see that class's Javadoc).
 *
 * <p>Implementation: {@link edu.harvard.hms.dbmi.avillach.ai.mcp.gateway.GatewayMcpToolGateway}, a real MCP client against
 * {@code pic-sure-mcp} via the gateway's {@code /mcp} route. Swapping it for another implementation (a different transport, a test double,
 * etc.) is a bean choice, not an API change for the rest of the service.
 */
public interface McpToolGateway {

    /**
     * The tools available to advertise to the model this turn.
     *
     * @param caller the caller's identity, replayed on the outbound gateway call (the gateway introspects every {@code /mcp} request)
     * @return the tool definitions
     */
    List<ToolDefinition> listTools(CallerContext caller);

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
