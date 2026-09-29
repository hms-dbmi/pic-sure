package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import io.modelcontextprotocol.common.McpTransportContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The {@code cross_count} tool: an obfuscated open-access count broken down into cells. The caller picks one of three cross-count result
 * types; {@code select} is passed on unchanged. Registered as a tool specification bean by {@code QueryToolConfig}, which calls
 * {@link #handle} through this bean so the audit aspect sees every call.
 */
@Component
public class CrossCountTool {

    /** The tool name. */
    public static final String NAME = "cross_count";

    /** The tool title. */
    public static final String TITLE = "Cross count";

    /** The tool description, with one complete worked query. */
    public static final String DESCRIPTION = """
        Break an open-access participant count down into cells, through PIC-SURE's open-access channel. Every cell is an obfuscated \
        open-access count that ignores the caller's consents and study access, read the same way as count_participants (count and \
        variance, or suppressed with threshold). resultType picks the breakdown. CATEGORICAL_CROSS_COUNT gives one cell per value of \
        each categorical concept filtered in the query. CONTINUOUS_CROSS_COUNT gives one cell per bin of each continuous concept \
        filtered in the query, and the open channel withholds the whole result (withheld true) when too few participants match. \
        CROSS_COUNT gives one cell per study consent concept: the open channel replaces select with every concept path under \
        \\_studies_consents\\, so the cells are keyed by those paths, and the \\_studies_consents\\ cell itself, the participants \
        matching the query across all studies, is kept rather than dropped. select is passed on unchanged, but the open channel uses it \
        for none of the three. At most 100 cells are returned, and cellsOmitted says how many were dropped. The query takes the same \
        shape as in count_participants, with no result type and no not field. Example arguments: \
        {"resultType":"CATEGORICAL_CROSS_COUNT","query":{"phenotypicClause":{"operator":"AND","phenotypicClauses":[\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\sex\\\\","values":["Female","Male"]},\
        {"operator":"OR","phenotypicClauses":[\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs000001\\\\age\\\\","min":40,"max":65},\
        {"phenotypicFilterType":"REQUIRED","conceptPath":"\\\\phs000001\\\\bmi\\\\"}]}]}}}""";

    private static final Logger log = LoggerFactory.getLogger(CrossCountTool.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final OpenQueryClient client;

    /**
     * Creates the tool.
     *
     * @param client the open query client
     */
    public CrossCountTool(OpenQueryClient client) {
        this.client = client;
    }

    /**
     * Binds the raw arguments and runs the cross count. Binding happens here so a rejected argument is a failure of the audited call.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param arguments the raw arguments of the {@code tools/call} request, possibly null
     * @return the capped, parsed cells
     * @throws ToolFailure with a model-facing message for an unbindable, missing or malformed argument, a failed open query call, or a
     *         response the tool cannot read
     */
    @AuditEvent(type = "QUERY", action = "query.sync")
    public CrossCountResult handle(McpTransportContext context, Map<String, Object> arguments) {
        Input input = QueryBinder.bind(arguments, Input.class);
        if (input == null || input.query() == null) {
            throw new ToolFailure("Argument 'query' is required.");
        }
        if (input.resultType() == null) {
            throw new ToolFailure("Argument 'resultType' is required.");
        }
        String resultType = input.resultType().name();
        Query query = input.query().toQuery(input.resultType().resultType());
        String body = QueryCalls.run(OpenQueryClient.QUERY_SYNC_PATH, () -> client.querySync(query, CallerHeaders.from(context)));
        if (body == null || body.isBlank()) {
            return CrossCountResult.withheld(resultType);
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("Open query response was not JSON: resultType={} path={}", resultType, OpenQueryClient.QUERY_SYNC_PATH);
            throw new ToolFailure("The query service returned a result the tool could not read.");
        }
        if (json == null || json.isNull()) {
            return CrossCountResult.withheld(resultType);
        }
        if (!json.isObject()) {
            log.warn("Open query response was not a JSON object: resultType={} path={}", resultType, OpenQueryClient.QUERY_SYNC_PATH);
            throw new ToolFailure("The query service returned a result the tool could not read.");
        }
        return CrossCountResult.from(resultType, json);
    }

    /** The cross-count result types a caller may pick. Each is one the open channel obfuscates. */
    public enum CrossCountType {

        /** One cell per study consent concept path. */
        CROSS_COUNT(ResultType.CROSS_COUNT),

        /** One cell per value of each categorical concept filtered in the query. */
        CATEGORICAL_CROSS_COUNT(ResultType.CATEGORICAL_CROSS_COUNT),

        /** One cell per bin of each continuous concept filtered in the query. */
        CONTINUOUS_CROSS_COUNT(ResultType.CONTINUOUS_CROSS_COUNT);

        private final ResultType resultType;

        CrossCountType(ResultType resultType) {
            this.resultType = resultType;
        }

        /**
         * The v3 result type this choice sends.
         *
         * @return the result type
         */
        public ResultType resultType() {
            return resultType;
        }
    }

    /**
     * The whole argument object of {@code cross_count}. The input schema is generated from this record as a root type, so the recursive
     * clause definitions sit at the schema root where their references resolve.
     *
     * @param query the query to break down
     * @param resultType the breakdown to run
     */
    public record Input(
        @Schema(description = "The query to break down") QueryInput query,
        @Schema(description = "CROSS_COUNT, CATEGORICAL_CROSS_COUNT, or CONTINUOUS_CROSS_COUNT") CrossCountType resultType
    ) {
    }
}
