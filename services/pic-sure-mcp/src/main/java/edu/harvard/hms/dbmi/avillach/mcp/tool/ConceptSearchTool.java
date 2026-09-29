package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryPage;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** MCP tool that searches the PIC-SURE data dictionary. */
@Component
public class ConceptSearchTool {

    /** Page size used when the model sends none. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** Largest page size the tool honors. */
    public static final int MAX_PAGE_SIZE = 25;


    private final DictionaryClient dictionary;

    /**
     * Creates the tool.
     *
     * @param dictionary the dictionary client
     */
    public ConceptSearchTool(DictionaryClient dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * Searches concepts by free text and returns a trimmed page the model can read.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param query the free-text search terms
     * @param page the zero-based page number, default 0
     * @param pageSize the results per page, default 10, at most 25
     * @return the page of concepts
     * @throws ToolFailure with a model-facing message for a bad argument or a failed dictionary call
     */
    @AuditEvent(type = "SEARCH", action = "concept.search")
    @McpTool(
        name = "search_concepts", description = """
            Search the PIC-SURE data dictionary for variables (concepts) by free text, for example \
            "systolic blood pressure" or "sex". Returns open-access dictionary metadata only, never participant data. \
            Each result has a conceptPath and a dataset. The conceptPath is the identifier get_concept, the count tools, and \
            get_adapter_code take. Categorical concepts list up to 20 values and report valuesOmitted when there are more, and \
            continuous concepts give min and max. Results are paged, 10 per page by default and at most 25, so request the next \
            page when total exceeds what you have.""", generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "Search concepts", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public ConceptSearchResult searchConcepts(
        McpTransportContext context, @McpToolParam(description = "Free-text search terms", required = true) String query,
        @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
        @McpToolParam(description = "Results per page, default 10, max 25", required = false) Integer pageSize
    ) {
        String text = ToolArguments.requireText("query", query, ToolArguments.MAX_QUERY_LENGTH);
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        DictionaryPage result = DictionaryCalls
            .run(DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConcepts(text, pageNumber, size, CallerHeaders.from(context)));
        return new ConceptSearchResult(
            text, pageNumber, size, result.total(), result.concepts().stream().map(ConceptSummary::summary).toList()
        );
    }
}
