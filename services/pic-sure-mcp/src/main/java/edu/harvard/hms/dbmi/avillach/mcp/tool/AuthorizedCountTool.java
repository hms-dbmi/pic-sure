package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.AuthorizedQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The {@code authorized_count_participants} tool: the authorized twin of {@code count_participants}. One exact participant count under the
 * caller's own consents, through the authorized channel with the caller's token. It always sends COUNT and drops {@code select}. Registered
 * as a tool specification bean by {@code QueryToolConfig}, which calls {@link #handle} through this bean so the audit aspect sees every
 * call.
 */
@Component
public class AuthorizedCountTool implements AuthorizedChannelTool {

    /** The tool name. */
    public static final String NAME = "authorized_count_participants";

    /** The tool title. */
    public static final String TITLE = "Count participants (authorized)";

    /** The tool description. The query format is the one {@code count_participants} documents. */
    public static final String DESCRIPTION = """
        Count the participants who match a query, as the signed-in user, through PIC-SURE's authorized channel. The number is exact and \
        filtered by the user's own consents and study access. It is not the obfuscated open-access count from count_participants, and \
        the two must never be compared or combined. It needs the user's token and fails without one. The query takes the same shape as \
        in count_participants: build it from conceptPath values that search_concepts returns, with no result type, no not field, and no \
        authorization fields. This tool always runs a COUNT and ignores select. Omit phenotypicClause to count every participant the \
        user may see.""";

    private final AuthorizedQueryClient client;

    /**
     * Creates the tool.
     *
     * @param client the authorized query client
     */
    public AuthorizedCountTool(AuthorizedQueryClient client) {
        this.client = client;
    }

    /**
     * Binds the raw arguments and runs the count.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param arguments the raw arguments of the {@code tools/call} request, possibly null
     * @return the exact count
     * @throws ToolFailure with a model-facing message for an unbindable or missing argument, a caller with no token, a failed query call,
     *         or a response that is not an exact count
     */
    @AuditEvent(type = "QUERY", action = "query.sync")
    public AuthorizedCountResult handle(McpTransportContext context, Map<String, Object> arguments) {
        // A caller who is not signed in hears that first, not how to fix a query they cannot run.
        CallerHeaders caller = CallerHeaders.from(context);
        AuthorizedQueryCalls.requireToken(caller);
        CountTool.Input input = QueryBinder.bind(arguments, CountTool.Input.class);
        ToolArguments.require("query", input == null ? null : input.query());
        Query query = input.query().toQuery(ResultType.COUNT);
        CountResult parsed = CountResult.parse(AuthorizedQueryCalls.run(client, query, caller));
        if (!parsed.isExact()) {
            throw new ToolFailure("The query service did not return an exact count.");
        }
        return new AuthorizedCountResult(parsed.count());
    }
}
