package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.AuthorizedQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryInput;
import io.modelcontextprotocol.common.McpTransportContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code authorized_cross_count} tool: the authorized twin of {@code cross_count}. An exact, consent-filtered count broken down into
 * cells, through the authorized channel with the caller's token. The caller picks one of four cross-count result types;
 * {@code OBSERVATION_CROSS_COUNT} is the one the open channel does not serve. Unlike the open channel, the authorized channel does not fill
 * {@code select} in, so CROSS_COUNT and OBSERVATION_CROSS_COUNT require it. Registered as a tool specification bean by
 * {@code QueryToolConfig}, which calls {@link #handle} through this bean so the audit aspect sees every call.
 */
@Component
public class AuthorizedCrossCountTool implements AuthorizedChannelTool {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The tool name. */
    public static final String NAME = "authorized_cross_count";

    /** The tool title. */
    public static final String TITLE = "Cross count (authorized)";

    /** The tool description. The query format is the one {@code count_participants} documents. */
    public static final String DESCRIPTION =
        """
            Break a participant count down into cells, as the signed-in user, through PIC-SURE's authorized channel. Every cell is an exact \
            count filtered by the user's own consents and study access, never an obfuscated open-access count from cross_count, and the two \
            must never be compared or combined. It needs the user's token and fails without one. resultType picks the breakdown. \
            CROSS_COUNT gives one cell per concept path in select: the participants matching the query who have a value for that concept; \
            a path the data service does not know also comes back as 0, so check a surprising 0 with search_concepts. \
            OBSERVATION_CROSS_COUNT gives the number of observations of each concept path in select; only this tool serves it. Both need \
            select, a non-empty list of conceptPath values from search_concepts, and fail without it. select takes at most 100 distinct \
            paths. CATEGORICAL_CROSS_COUNT gives one cell per value of each categorical concept filtered in the query, and \
            CONTINUOUS_CROSS_COUNT one cell per distinct observed value of each continuous concept filtered in the query, in ascending order \
            and not binned; neither uses select, and both need a phenotypicClause that filters on such a concept. A cell with noData true means there are no values for that concept in the cohort, or the \
            concept has no data here: report it as none found, and check the path with search_concepts if that is unexpected. A cell with \
            failed true could not be counted and is not a zero. Cells are listed concept by concept. At most 100 are returned, and \
            cellsOmitted says how many were dropped from the end of the list, so the last concept listed may be cut short and later ones \
            missing; never read a cut-short concept as its whole distribution. The query takes the same shape as in count_participants, \
            with no result type, no not field, and no authorization fields. Build it from conceptPath values that search_concepts returns.""";

    /** The result types whose cells are the concept paths in {@code select}, so an empty {@code select} would count nothing. */
    private static final Set<CrossCountType> SELECT_DRIVEN = EnumSet.of(CrossCountType.CROSS_COUNT, CrossCountType.OBSERVATION_CROSS_COUNT);

    private static final Logger log = LoggerFactory.getLogger(AuthorizedCrossCountTool.class);

    private final AuthorizedQueryClient client;

    /**
     * Creates the tool.
     *
     * @param client the authorized query client
     */
    public AuthorizedCrossCountTool(AuthorizedQueryClient client) {
        this.client = client;
    }

    /**
     * Binds the raw arguments and runs the cross count.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param arguments the raw arguments of the {@code tools/call} request, possibly null
     * @return the capped, parsed cells
     * @throws ToolFailure with a model-facing message for an unbindable, missing or malformed argument, a caller with no token, a failed
     *         query call, a select-driven type without select or with too many paths, a categorical or continuous type without a
     *         phenotypicClause, a breakdown that finds no concept of its kind, or a response the tool cannot read
     */
    @AuditEvent(type = "QUERY", action = "query.sync")
    public AuthorizedCrossCountResult handle(McpTransportContext context, Map<String, Object> arguments) {
        // A caller who is not signed in hears that first, not how to fix a query they cannot run.
        CallerHeaders caller = CallerHeaders.from(context);
        AuthorizedQueryCalls.requireToken(caller);
        Input input = QueryBinder.bind(arguments, Input.class);
        ToolArguments.require("query", input == null ? null : input.query());
        ToolArguments.require("resultType", input.resultType());
        String resultType = input.resultType().name();
        boolean selectDriven = SELECT_DRIVEN.contains(input.resultType());
        List<String> selected =
            !selectDriven || input.query().select() == null ? List.of() : input.query().select().stream().distinct().toList();
        if (selectDriven && selected.isEmpty()) {
            throw new ToolFailure(resultType + " counts the concept paths in select. Pass select with at least one conceptPath.");
        }
        if (selectDriven && selected.size() > CrossCountResult.MAX_CELLS) {
            throw new ToolFailure(resultType + " takes at most " + CrossCountResult.MAX_CELLS + " distinct concept paths in select.");
        }
        if (!selectDriven && input.query().phenotypicClause() == null) {
            throw new ToolFailure(
                resultType + " breaks down the concepts the query filters on. Add a phenotypicClause that filters on one."
            );
        }
        // The categorical and continuous types do not use select, so it is not sent for them.
        QueryInput sent = new QueryInput(selectDriven ? selected : null, input.query().phenotypicClause(), input.query().genomicFilters());
        Query query = sent.toQuery(input.resultType().resultType());
        String body = AuthorizedQueryCalls.run(client, query, caller);
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("Authorized query response was not JSON: resultType={}", resultType);
            throw new ToolFailure("The query service returned a result the tool could not read.");
        }
        if (json == null || !json.isObject()) {
            log.warn("Authorized query response was not a JSON object: resultType={}", resultType);
            throw new ToolFailure("The query service returned a result the tool could not read.");
        }
        if (!selectDriven && json.isEmpty()) {
            // HPDS breaks down a categorical FILTER with values, or a REQUIRED filter on a categorical path it knows, and any filter on a
            // continuous path it knows; it lists each such concept, even one with no values, so an empty body means it found none.
            throw new ToolFailure(
                input.resultType() == CrossCountType.CATEGORICAL_CROSS_COUNT ? resultType
                    + " found no categorical concept to break down. It breaks down categorical concepts the query filters with "
                    + "values or as REQUIRED, not ANY_RECORD_OF, on paths this data service knows. Check each path with search_concepts."
                    : resultType
                        + " found no continuous concept to break down. It breaks down continuous concepts the query filters on, on "
                        + "paths this data service knows. Check each path with search_concepts."
            );
        }
        return AuthorizedCrossCountResult.from(resultType, json, selectDriven ? selected : List.of());
    }

    /** The cross-count result types a caller may pick. */
    public enum CrossCountType {

        /** One cell per concept path in {@code select}. */
        CROSS_COUNT(ResultType.CROSS_COUNT),

        /** One cell per value of each categorical concept filtered in the query. */
        CATEGORICAL_CROSS_COUNT(ResultType.CATEGORICAL_CROSS_COUNT),

        /** One cell per distinct observed value of each continuous concept filtered in the query. */
        CONTINUOUS_CROSS_COUNT(ResultType.CONTINUOUS_CROSS_COUNT),

        /** Observations per concept path in {@code select}. Not served by the open channel. */
        OBSERVATION_CROSS_COUNT(ResultType.OBSERVATION_CROSS_COUNT);

        private final ResultType resultType;

        CrossCountType(ResultType resultType) {
            this.resultType = resultType;
        }

        /**
         * @return the v3 result type this choice sends
         */
        public ResultType resultType() {
            return resultType;
        }
    }

    /**
     * The whole argument object of {@code authorized_cross_count}. The input schema is generated from this record as a root type, so the
     * recursive clause definitions sit at the schema root where their references resolve.
     *
     * @param query the query to break down
     * @param resultType the breakdown to run
     */
    public record Input(
        @Schema(
            description = "The query to break down. CROSS_COUNT and OBSERVATION_CROSS_COUNT count the concept paths in its select"
        ) QueryInput query,
        @Schema(
            description = "CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT, or OBSERVATION_CROSS_COUNT"
        ) CrossCountType resultType
    ) {
    }
}
