package edu.harvard.hms.dbmi.avillach.ai.mcp.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.ai.chat.CallerContext;
import edu.harvard.hms.dbmi.avillach.ai.mcp.McpToolGateway;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolDefinition;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

/**
 * Stage-2 placeholder for a real MCP client against {@code pic-sure-mcp} via the gateway's {@code /mcp} route. Active by default
 * ({@code picsure.ai.mcp.mode=mock}); delete this class (and flip the property) once that real client exists.
 *
 * <p>Its tool defs mirror {@code pic-sure-mcp}'s real tools by name and rough result shape -- {@code search_concepts}, {@code list_facets},
 * {@code count_participants}, {@code cross_count} (see {@code pic-sure-mcp/.../mcp/tool/*.java}) -- plus one tool that does not exist on
 * the real server yet, {@link #PROPOSE_QUERY_TOOL}, standing in for the query/facets/search proposal tool this plan assumes the MCP server
 * will add (see the Stage-2 plan: {@code ai-service} has no locally-handled tool of its own). Only this mock's tool list needs to change
 * once the real server picks an actual name for it -- no dispatch logic in this service is name-aware.
 *
 * <p>One response ({@link #searchConcepts}) embeds the caller's identity so two different callers visibly get two different (mocked)
 * results -- otherwise the integration test for that behavior (Story 2 AC) would be vacuous once this mock is in the loop.
 */
@Component
@ConditionalOnProperty(prefix = "picsure.ai.mcp", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockMcpToolGateway implements McpToolGateway {

    /** Placeholder name for the query/facets/search proposal tool -- not a real MCP tool name yet. */
    public static final String PROPOSE_QUERY_TOOL = "propose_query";

    private static final String OBJECT_SCHEMA = "{\"type\":\"object\"}";
    private static final String QUERY_SCHEMA = """
        {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""";
    private static final String COUNT_SCHEMA = """
        {"type":"object","properties":{"query":{"type":"object"}},"required":["query"]}""";
    private static final String PROPOSE_SCHEMA = """
        {"type":"object","properties":{"query":{"type":"object"},"facets":{"type":"array"},"search":{"type":"object"}}}""";

    private final ObjectMapper objectMapper;

    public MockMcpToolGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolDefinition> listTools() {
        return List.of(
            new ToolDefinition(
                "search_concepts", "Search the PIC-SURE data dictionary for variables (concepts) by free text.", QUERY_SCHEMA
            ),
            new ToolDefinition(
                "list_facets", "List the data dictionary's facet categories and how many concepts each matches.", QUERY_SCHEMA
            ),
            new ToolDefinition(
                "count_participants", "Count the participants who match a query, an obfuscated open-access count.", COUNT_SCHEMA
            ),
            new ToolDefinition("cross_count", "Cross-tabulate an obfuscated open-access count by a second concept's values.", COUNT_SCHEMA),
            new ToolDefinition(
                PROPOSE_QUERY_TOOL,
                "Propose an updated query, facet selection, or search for the researcher to review -- call this when the "
                    + "conversation should update what the user sees, not just answer in prose.",
                PROPOSE_SCHEMA
            )
        );
    }

    @Override
    public ToolResult callTool(String name, String argumentsJson, CallerContext caller) {
        return switch (name) {
            case "search_concepts" -> ToolResult.success(write(searchConcepts(caller)));
            case "list_facets" -> ToolResult.success(write(listFacets()));
            case "count_participants" -> ToolResult.success(write(countParticipants()));
            case "cross_count" -> ToolResult.success(write(crossCount()));
            case PROPOSE_QUERY_TOOL -> ToolResult.success(write(proposeQuery()));
            default -> ToolResult.failure("Unknown tool: " + name);
        };
    }

    private Map<String, Object> searchConcepts(CallerContext caller) {
        Map<String, Object> concept = new LinkedHashMap<>();
        concept.put("conceptPath", "\\mock-study\\systolic blood pressure\\");
        concept.put("display", "Systolic blood pressure");
        concept.put("dataset", identityOf(caller));
        concept.put("type", "continuous");
        concept.put("min", 80.0);
        concept.put("max", 200.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", "blood pressure");
        result.put("page", 0);
        result.put("pageSize", 10);
        result.put("total", 1);
        result.put("concepts", List.of(concept));
        return result;
    }

    private Map<String, Object> listFacets() {
        Map<String, Object> facet = new LinkedHashMap<>();
        facet.put("name", "mock-study");
        facet.put("display", "Mock study");
        facet.put("count", 1);

        Map<String, Object> category = new LinkedHashMap<>();
        category.put("name", "study");
        category.put("display", "Study");
        category.put("facets", List.of(facet));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", "");
        result.put("categories", List.of(category));
        return result;
    }

    private Map<String, Object> countParticipants() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("display", "120 ±3");
        result.put("count", 120);
        result.put("variance", 3);
        result.put("suppressed", false);
        return result;
    }

    private Map<String, Object> crossCount() {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("conceptPath", "\\mock-study\\sex\\");
        cell.put("category", "Female");
        cell.put("count", Map.of("display", "60 ±3", "count", 60, "variance", 3, "suppressed", false));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "CATEGORICAL_CROSS_COUNT");
        result.put("totalCells", 1);
        result.put("cells", List.of(cell));
        return result;
    }

    private Map<String, Object> proposeQuery() {
        // Stubbed per Story 3's AC: a shaped but unvalidated object. Real validation is Story 4, once
        // this tool exists for real -- see the Stage-2 plan.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", Map.of("phenotypicClause", Map.of("operator", "AND", "phenotypicClauses", List.of())));
        result.put("validated", false);
        return result;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** The caller's verified id when the gateway supplied one, else a short, non-reversible digest of the bearer token. */
    private static String identityOf(CallerContext caller) {
        if (caller.userId() != null && !caller.userId().isBlank()) {
            return caller.userId();
        }
        return "anon-" + shortDigest(caller.authorization());
    }

    private static String shortDigest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
