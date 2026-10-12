package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryPage;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.FacetFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool that searches the PIC-SURE data dictionary. */
@Component
public class ConceptSearchTool {

    /** Page size used when the model sends none. */
    public static final int DEFAULT_PAGE_SIZE = 25;

    /** Largest page size the tool honors. */
    public static final int MAX_PAGE_SIZE = 50;

    /** A search with more |-separated terms than this gets no termTotals: each term costs one extra dictionary call. */
    static final int MAX_COUNTED_TERMS = 8;

    /** Most facets one search can be narrowed by. */
    static final int MAX_FACETS = 10;

    /** Longest facet category or name accepted, in characters. */
    static final int MAX_FACET_TEXT_LENGTH = 200;

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
     * @param pageSize the results per page, default 25, at most 50
     * @param facets the facets to narrow the search by, as {@code list_facets} returned them; none by default
     * @return the page of concepts
     * @throws ToolFailure with a model-facing message for a bad argument or a failed dictionary call
     */
    @AuditEvent(type = "SEARCH", action = "concept.search")
    @McpTool(
        name = "search_concepts", description = """
            Search the PIC-SURE data dictionary for variables (concepts) by free text, for example \
            "systolic blood pressure" or "sex". Separate alternative terms with | to OR them in one call, for example \
            "creatinine | albumin" or "leukocytes | white blood cell"; the words within one term must all match. Matching is \
            on the text, so an abbreviation such as "BMI" can return no results while "body mass index" matches: if total \
            is 0, retry once with the spelled-out term and synonyms joined by |. A search with several |-separated terms also returns \
            termTotals, how many concepts each term matches on its own: a term at 0 matched nothing, even when total is above 0, so \
            retry that term at most once with synonyms and otherwise report it as not found. Returns \
            open-access dictionary metadata only, never participant data. Each result has a conceptPath, the identifier \
            get_concept, the count tools, and get_adapter_code take. display and dataset are left out when they equal the \
            conceptPath's last and first segments. Categorical concepts list up to 20 values and report valuesOmitted when there \
            are more, and continuous concepts give min and max. Results are paged, 25 per page by default and at most 50; leave \
            pageSize out unless you need more, and request the next page when total exceeds what you have. To narrow a search to a study, dataset or data type, \
            pass facets, a list of {category, name} pairs copied exactly from list_facets; a facet narrows every term in the search. With facets, query may be empty to browse everything in the facet; never invent a term or send "*" just to fill it.""", generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "Search concepts", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public ConceptSearchResult searchConcepts(
        McpTransportContext context, @McpToolParam(description = "Free-text search terms; separate alternatives with |. May be left out only when facets are given", required = false) String query,
        @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
        @McpToolParam(description = "Results per page, default 25, max 50", required = false) Integer pageSize,
        @McpToolParam(
            description = "Facets to narrow by: {category, name} pairs copied exactly from list_facets; omit for none", required = false
        ) List<FacetFilter> facets
    ) {
        List<FacetFilter> narrowing = checkedFacets(facets);
        // A facet alone is a browse: the dictionary takes an empty search, as the UI sends when only a facet is picked.
        String text = narrowing.isEmpty() ? ToolArguments.requireText("query", query, ToolArguments.MAX_QUERY_LENGTH)
            : ToolArguments.optionalText("query", query, ToolArguments.MAX_QUERY_LENGTH);
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        DictionaryPage result = DictionaryCalls.run(
            DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConcepts(text, narrowing, pageNumber, size, CallerHeaders.from(context))
        );
        return new ConceptSearchResult(
            pageNumber, result.total(), result.concepts().stream().map(ConceptSummary::summary).toList(),
            termTotals(text, narrowing, context)
        );
    }

    /**
     * Spring AI does not validate arguments against the input schema, so a facet list is checked here: bounded in count, and each facet
     * needs a category and a name of bounded length with no control characters.
     *
     * @return the facets to send, empty when the model sent none
     * @throws ToolFailure if there are too many facets or one is missing a field
     */
    private static List<FacetFilter> checkedFacets(List<FacetFilter> facets) {
        if (facets == null || facets.isEmpty()) {
            return List.of();
        }
        if (facets.size() > MAX_FACETS) {
            throw new ToolFailure("Argument 'facets' must have at most " + MAX_FACETS + " entries.");
        }
        return facets.stream().map(facet -> {
            if (facet == null) {
                throw new ToolFailure("Argument 'facets' must not hold a null entry.");
            }
            return new FacetFilter(
                ToolArguments.requireText("facets.category", facet.category(), MAX_FACET_TEXT_LENGTH),
                ToolArguments.requireText("facets.name", facet.name(), MAX_FACET_TEXT_LENGTH)
            );
        }).toList();
    }

    /**
     * An OR search's single total hides a term that matched nothing (the model then keeps rephrasing it) and a term whose matches sit
     * past the first page (the model then reports it missing). Each term's own total, from a one-row search, shows both.
     *
     * @return each term's total in the order given, or null for a single term, too many terms, or a failed lookup (the search itself
     *         already succeeded, so it is not failed over this)
     */
    private Map<String, Long> termTotals(String text, List<FacetFilter> facets, McpTransportContext context) {
        List<String> terms = Arrays.stream(text.split("\\|")).map(String::strip).filter(term -> !term.isEmpty()).distinct().toList();
        if (terms.size() < 2 || terms.size() > MAX_COUNTED_TERMS) {
            return null;
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        try {
            for (String term : terms) {
                totals.put(term, DictionaryCalls.run(
                    DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConcepts(term, facets, 0, 1, CallerHeaders.from(context))
                ).total());
            }
        } catch (ToolFailure e) {
            return null;
        }
        return totals;
    }
}
