package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.FacetCategory;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** MCP tool that lists the dictionary's facet categories and counts for a search. */
@Component
public class FacetTool {

    /** Most facet categories a result carries. */
    public static final int MAX_CATEGORIES = 25;

    /** Most facets a result carries per category. */
    public static final int MAX_FACETS_PER_CATEGORY = 25;


    private final DictionaryClient dictionary;

    /**
     * Creates the tool.
     *
     * @param dictionary the dictionary client
     */
    public FacetTool(DictionaryClient dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Lists facet categories and counts for a search, capped so one call cannot fill the client's context.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param query the free-text search terms, or null for all concepts
     * @return the capped facet categories
     * @throws ToolFailure with a model-facing message for a bad argument or a failed dictionary call
     */
    @McpTool(
        name = "list_facets", description = """
            List the PIC-SURE data dictionary's facet categories, such as study or data type, with the number of \
            concepts each facet matches for an optional free-text search. Returns open-access dictionary metadata only, \
            never participant data. Use it to see how a search spreads across studies before calling search_concepts. \
            At most 25 categories and 25 facets per category come back, and the omitted counts say how many were dropped.""",
        generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "List facets", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public FacetResult listFacets(
        McpTransportContext context,
        @McpToolParam(description = "Free-text search terms, or empty for all concepts", required = false) String query
    ) {
        String text = ToolArguments.optionalText("query", query, ToolArguments.MAX_QUERY_LENGTH);
        List<FacetCategory> categories =
            DictionaryCalls.run(DictionaryClient.FACETS_PATH, () -> dictionary.listFacets(text, CallerHeaders.from(context)));
        List<FacetResult.Category> kept = categories.stream().limit(MAX_CATEGORIES).map(FacetTool::category).toList();
        Integer omitted = categories.size() > MAX_CATEGORIES ? categories.size() - MAX_CATEGORIES : null;
        return new FacetResult(text, kept, omitted);
    }

    private static FacetResult.Category category(FacetCategory category) {
        List<FacetCategory.Facet> facets = category.facets() == null ? List.of() : category.facets();
        List<FacetResult.Facet> kept =
            facets.stream().limit(MAX_FACETS_PER_CATEGORY).map(f -> new FacetResult.Facet(f.name(), f.display(), f.count())).toList();
        Integer omitted = facets.size() > MAX_FACETS_PER_CATEGORY ? facets.size() - MAX_FACETS_PER_CATEGORY : null;
        return new FacetResult.Category(category.name(), category.display(), category.description(), kept, omitted);
    }
}
