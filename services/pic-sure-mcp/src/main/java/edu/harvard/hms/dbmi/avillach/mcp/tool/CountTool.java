package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import io.modelcontextprotocol.common.McpTransportContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Component;

/**
 * The {@code count_participants} tool: one obfuscated open-access participant count for a query. It always sends COUNT, whatever the input
 * holds, and drops {@code select}. Registered as a tool specification bean by {@code QueryToolConfig}, which binds the arguments and calls
 * {@link #handle} through this bean.
 */
@Component
public class CountTool {

    /** The tool name. */
    public static final String NAME = "count_participants";

    /** The tool title. */
    public static final String TITLE = "Count participants";

    /** The tool description, with one complete worked query. */
    public static final String DESCRIPTION = """
        Count the participants who match a query, through PIC-SURE's open-access channel. The number is an obfuscated open-access \
        count that ignores the caller's consents and study access: it carries a small random offset (count, with variance giving the \
        band the true count lies in), and a count below the threshold is hidden (suppressed true, with threshold). \
        Build the query from conceptPath values that search_concepts returns. A clause is either a filter (phenotypicFilterType and \
        conceptPath, plus values for a categorical FILTER or min and max for a numeric FILTER) or a subquery (operator AND or OR, and \
        phenotypicClauses, each a filter or another subquery). genomicFilters are allowed. The query has no result type and no not \
        field: this tool always runs a COUNT and ignores select. Omit phenotypicClause to count every participant. Example arguments: \
        {"query":{"phenotypicClause":{"operator":"AND","phenotypicClauses":[\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\sex\\\\","values":["Female"]},\
        {"operator":"OR","phenotypicClauses":[\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\age\\\\","min":40,"max":65},\
        {"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\phs000001\\\\bmi\\\\"}]}]}}}""";

    private final OpenQueryClient client;

    /**
     * Creates the tool.
     *
     * @param client the open query client
     */
    public CountTool(OpenQueryClient client) {
        this.client = client;
    }

    /**
     * Runs the count.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param input the bound arguments
     * @return the parsed obfuscated count
     * @throws ToolFailure with a model-facing message for a missing or malformed query or a failed open query call
     */
    public CountResult handle(McpTransportContext context, Input input) {
        if (input == null || input.query() == null) {
            throw new ToolFailure("Argument 'query' is required.");
        }
        Query query = input.query().toQuery(ResultType.COUNT);
        String body = QueryCalls.run(OpenQueryClient.QUERY_SYNC_PATH, () -> client.querySync(query, CallerHeaders.from(context)));
        if (body == null || body.isBlank()) {
            throw new ToolFailure("The query service returned no count.");
        }
        return CountResult.parse(body);
    }

    /**
     * The whole argument object of {@code count_participants}. The input schema is generated from this record as a root type, so the
     * recursive clause definitions sit at the schema root where their references resolve.
     *
     * @param query the query to count
     */
    public record Input(@Schema(description = "The query to count") QueryInput query) {
    }
}
