package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

/**
 * The query/facets/search proposal tool. Handled entirely inside {@code ai-service} -- it is never dispatched to {@code McpToolGateway},
 * because it is not a data-access call at all: it is the model handing back structured state for the researcher to review (see
 * {@code ToolUseLoopService}, which advertises this tool's {@link #definition()} alongside the gateway's and routes a call to
 * {@link #propose(String)} by name instead of the gateway).
 *
 * <p>Arguments are bound and validated for real ({@link ProposeQueryInput}/{@link ProposeQueryBinder}, mirroring {@code pic-sure-mcp}'s own
 * query binder) -- but, deliberately, see {@code ToolUseLoopService}'s class Javadoc: a successful, shape-valid proposal is still not
 * promoted into {@code ChatResponse}'s structured fields. That needs real-execution validation (confirming the query actually runs against
 * HPDS, which a shape check alone can't prove), and that doesn't exist yet.
 */
@Component
public class ProposeQueryTool {

    /** The tool name. */
    public static final String NAME = "propose_query";

    private static final String DESCRIPTION = """
        Propose an updated query, facet selection, or search for the researcher to review -- call this when the conversation should \
        update what the user sees, not just answer in prose. query is the same shape search_concepts/count_participants use (select, \
        phenotypicClause, genomicFilters); build it from conceptPath values that search_concepts returns. facets is a list of \
        {category, name} pairs. search is free-form dictionary-search state. All three fields are optional -- include only what \
        actually changed this turn.""";

    private static final String SCHEMA = """
        {"type":"object","properties":{"query":{"type":"object"},"facets":{"type":"array"},"search":{"type":"object"}}}""";

    private final ObjectMapper objectMapper;

    public ProposeQueryTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The tool definition advertised to the model.
     *
     * @return the tool definition
     */
    public ToolDefinition definition() {
        return new ToolDefinition(NAME, DESCRIPTION, SCHEMA);
    }

    /**
     * Binds and validates a proposal's arguments. Never throws -- an invalid proposal is a model-facing {@link ToolResult#failure}, the
     * same as a failed MCP lookup.
     *
     * @param argumentsJson the raw arguments the model sent, as a JSON string
     * @return a success result once the shape is valid, or a failure naming what's wrong
     */
    public ToolResult propose(String argumentsJson) {
        try {
            Map<String, Object> arguments = parseArguments(argumentsJson);
            Arguments bound = ProposeQueryBinder.bind(arguments, Arguments.class);
            if (bound.query() != null) {
                bound.query().validate();
            }
            requireValidFacets(bound.facets());
            requireSearchIsAnObject(bound.search());
            return ToolResult.success("{\"received\":true}");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure("The proposal could not be read. Check it against the tool's input schema.");
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("The arguments could not be read. Check them against the tool's input schema.");
        }
    }

    private void requireValidFacets(List<FacetSelection> facets) {
        if (facets == null) {
            return;
        }
        for (FacetSelection facet : facets) {
            boolean blank = facet == null || isBlank(facet.category()) || isBlank(facet.name());
            if (blank) {
                throw new IllegalArgumentException("Each facet requires non-blank 'category' and 'name'.");
            }
        }
    }

    private void requireSearchIsAnObject(JsonNode search) {
        if (search != null && !search.isNull() && !search.isObject()) {
            throw new IllegalArgumentException("Field 'search' must be a JSON object.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Arguments(ProposeQueryInput query, List<FacetSelection> facets, JsonNode search) {
    }
}
