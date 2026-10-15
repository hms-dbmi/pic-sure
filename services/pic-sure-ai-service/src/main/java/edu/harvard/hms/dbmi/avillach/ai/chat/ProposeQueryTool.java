package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

/**
 * The query/facets/search proposal tool. Handled entirely inside {@code ai-service} -- it is never dispatched to {@code McpToolGateway},
 * because it is not a data-access call at all: it is the model handing back structured state for the researcher to review (see
 * {@code ToolUseLoopService}, which advertises this tool's {@link #definition()} alongside the gateway's and routes a call to
 * {@link #propose(String, KnownFacets)} by name instead of the gateway).
 *
 * <p>Arguments are bound and validated for real ({@link ProposeQueryInput}/{@link ProposeQueryBinder}, mirroring {@code pic-sure-mcp}'s own
 * query binder), and every proposed facet must have been shown to the model this turn ({@link KnownFacets}; concept paths get the same
 * treatment from {@link ConceptPaths} in the loop). A proposal that passes comes back as an {@link Outcome} for the loop to promote into
 * {@code ChatResponse}.
 */
@Component
public class ProposeQueryTool {

    /** The tool name. */
    public static final String NAME = "propose_query";

    private static final String DESCRIPTION = """
        Propose an updated query, facet selection, or search for the researcher to review -- call this when the conversation should \
        update what the user sees, not just answer in prose. This is the only way to hand over a query; never write query JSON in a reply. All three fields are optional -- include only what actually changed this \
        turn. query has select (every concept path to show: keep the researcher's current ones unless they ask to remove one), phenotypicClause, and genomicFilters. A phenotypicClause is either a filter or \
        a subquery. A filter has phenotypicFilterType, conceptPath, and, for FILTER, either values (categorical) or min and max \
        (numeric); REQUIRED and ANY_RECORD_OF take neither. A subquery has operator (AND or OR) and phenotypicClauses, each a filter \
        or another subquery. A filter with not set to true excludes the participants it matches; keep not exactly as it is on every \
        filter you carry over from the researcher's query. Build every conceptPath from search_concepts results, exactly as returned. facets \
        is a list of {category, name} pairs taken from list_facets; it narrows the researcher's table of variables to those facets. search \
        is the dictionary search text, a plain string, and the researcher's screen shows the variables it matches as a table they can page \
        through: send the text that found the variables in search_concepts (several terms joined by |), or an empty string to clear it. \
        For a lookup (show, find, or list variables), send only search, never a query. Example arguments (the \
        phs999999 paths are placeholders only; never use them, use real conceptPath values from search_concepts): \
        {"query":{"select":["\\\\phs999999\\\\bmi\\\\"],"phenotypicClause":{"operator":"AND","phenotypicClauses":[\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs999999\\\\sex\\\\","values":["Female"]},\
        {"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs999999\\\\age\\\\","min":40,"max":65}]}}}""";

    private static final String SCHEMA = """
        {"type":"object","properties":{"query":{"type":"object"},"facets":{"type":"array"},"search":{"type":"string"}}}""";

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
     * A proposal that passed every check, each part null when the model didn't send it.
     *
     * @param query the proposed query
     * @param facets the proposed facet selection
     * @param search the proposed search text
     */
    record Proposal(ProposeQueryInput query, List<FacetSelection> facets, String search) {

        Proposal withQuery(ProposeQueryInput replacement) {
            return new Proposal(replacement, facets, search);
        }

        Proposal withSearch(String replacement) {
            return new Proposal(query, facets, replacement);
        }

        Proposal withFacets(List<FacetSelection> replacement) {
            return new Proposal(query, replacement, search);
        }
    }

    /**
     * What one call to {@link #propose} decided.
     *
     * @param result what to feed back to the model
     * @param proposal the accepted proposal, or null when {@code result} is a failure
     */
    record Outcome(ToolResult result, Proposal proposal) {

        private static Outcome rejected(String message) {
            return new Outcome(ToolResult.failure(message), null);
        }
    }

    /**
     * Binds and validates a proposal's arguments: shape first, then that every proposed facet was shown to the model this turn. (A
     * proposed concept path is checked earlier, by the loop, before this is called.) Never throws -- an invalid proposal is a model-facing
     * failure, the same as a failed MCP lookup.
     *
     * @param argumentsJson the raw arguments the model sent, as a JSON string
     * @param knownFacets the facets this turn has seen
     * @return a success result carrying the accepted proposal once every check passes, or a failure naming what's wrong
     */
    Outcome propose(String argumentsJson, KnownFacets knownFacets) {
        try {
            Map<String, Object> arguments = parseArguments(argumentsJson);
            dropEmptyPlaceholders(arguments);
            liftQueryFields(arguments);
            Arguments bound = ProposeQueryBinder.bind(arguments, Arguments.class);
            if (bound.query() == null && bound.facets() == null && bound.search() == null) {
                return Outcome.rejected("The proposal is empty. Send at least one of query, facets, or search.");
            }
            if (bound.query() != null) {
                bound.query().validate();
            }
            ChatStateValidator.requireValidFacets(bound.facets());
            ChatStateValidator.requireValidSearch(bound.search());
            if (bound.facets() != null) {
                FacetSelection unknown = knownFacets.firstUnknownIn(bound.facets());
                if (unknown != null) {
                    return Outcome.rejected(unknownFacetMessage(unknown));
                }
            }
            return new Outcome(
                ToolResult.success("{\"received\":true}"), new Proposal(bound.query(), bound.facets(), bound.search())
            );
        } catch (IllegalArgumentException e) {
            return Outcome.rejected(e.getMessage());
        } catch (RuntimeException e) {
            return Outcome.rejected("The proposal could not be read. Check it against the tool's input schema.");
        }
    }

    /** Small models send {@code {}} for a field they mean to leave out. */
    @SuppressWarnings("unchecked")
    private static void dropEmptyPlaceholders(Map<String, Object> arguments) {
        arguments.remove("search", Map.of());
        if (arguments.get("query") instanceof Map<?, ?> query) {
            ((Map<String, Object>) query).remove("phenotypicClause", Map.of());
        }
    }

    /** gpt-oss sometimes sends the query's fields at the top level instead of inside {@code query}. */
    private static void liftQueryFields(Map<String, Object> arguments) {
        if (arguments.containsKey("query") || ProposeQueryInput.FIELDS.stream().noneMatch(arguments::containsKey)) {
            return;
        }
        Map<String, Object> query = new LinkedHashMap<>();
        ProposeQueryInput.FIELDS.forEach(field -> {
            if (arguments.containsKey(field)) {
                query.put(field, arguments.remove(field));
            }
        });
        arguments.put("query", query);
    }

    static String unknownFacetMessage(FacetSelection unknown) {
        return "Unknown facet: category '" + clip(unknown.category()) + "', name '" + clip(unknown.name())
            + "'. A facet must come from a list_facets result in this conversation, exactly as returned. Call "
            + "list_facets first, wait for its results, then use a category and name from them.";
    }

    static String clip(String value) {
        String printable = value.codePoints().filter(c -> !Character.isISOControl(c))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return printable.length() > ProposeQueryBinder.MAX_FIELD_NAME_LENGTH ? printable.substring(0, ProposeQueryBinder.MAX_FIELD_NAME_LENGTH)
            : printable;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("The arguments could not be read. Check them against the tool's input schema.");
        }
    }

    private record Arguments(ProposeQueryInput query, List<FacetSelection> facets, String search) {
    }
}
