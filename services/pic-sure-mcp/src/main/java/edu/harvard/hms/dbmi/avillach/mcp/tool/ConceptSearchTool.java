package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryPage;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.MDC;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** MCP tool that searches the PIC-SURE data dictionary. */
@Component
public class ConceptSearchTool {

    /** Page size used when the model sends none. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** Largest page size the tool honors, and the most concepts a several-term search returns. */
    public static final int MAX_PAGE_SIZE = 25;

    /** Most terms one call may search. */
    public static final int MAX_TERMS = 5;


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
     * Searches concepts by free text and returns a trimmed page the model can read. Exactly one of {@code query} and {@code terms} must be
     * given. With {@code terms}, each term is searched in parallel, and the pages are merged as {@link TermPages} describes.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param query the free-text search terms, all of which must match
     * @param terms up to {@value #MAX_TERMS} separate searches, such as synonyms and abbreviations
     * @param page the zero-based page number, default 0
     * @param pageSize the results per page, default 10, at most 25
     * @return the page of concepts
     * @throws ToolFailure with a model-facing message for a bad argument, a failed dictionary call, or, with {@code terms}, when every
     *         term's call failed
     */
    @AuditEvent(type = "SEARCH", action = "concept.search")
    @McpTool(
        name = "search_concepts", description = """
            Search the PIC-SURE data dictionary for variables (concepts) by free text, for example \
            "systolic blood pressure" or "sex". Returns open-access dictionary metadata only, never participant data. \
            Each result has a conceptPath and a dataset. The conceptPath is the identifier get_concept, the count tools, and \
            get_adapter_code take. Categorical concepts list up to 20 values and report valuesOmitted when there are more, and \
            continuous concepts give min and max. Results are paged, 10 per page by default and at most 25, so request the next \
            page when total exceeds what you have. \
            The dictionary ANDs every word of a search with prefix matching, so a multi-word search must describe one concept: \
            "blood pressure hypertension" finds nothing, and "blood pressure" misses concepts named "BP". Search synonyms and \
            abbreviations as separate terms, which the terms argument does in one call. Give either query or terms (up to 5), \
            not both. With terms, each concept lists the matchedTerms that returned it, total is the sum of the per-term totals, \
            page and pageSize apply to each term, at most 25 concepts come back with truncated true when more matched, and a \
            term whose search failed is named in warnings.""", generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "Search concepts", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public ConceptSearchResult searchConcepts(
        McpTransportContext context,
        @McpToolParam(
            description = "Free-text search; every word must match. Give query or terms, not both", required = false
        ) String query,
        @McpToolParam(
            description = "Up to 5 separate searches, such as synonyms and abbreviations, run in one call. Give query or terms, not both",
            required = false
        ) List<String> terms, @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
        @McpToolParam(description = "Results per page, default 10, max 25", required = false) Integer pageSize
    ) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasTerms = terms != null && !terms.isEmpty();
        if (hasQuery == hasTerms) {
            throw new ToolFailure(hasQuery ? "Give either 'query' or 'terms', not both." : "Give either 'query' or 'terms'.");
        }
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        CallerHeaders caller = CallerHeaders.from(context);
        if (hasTerms) {
            List<String> searchTerms = validTerms(terms);
            return TermPages.merge(searchAll(searchTerms, pageNumber, size, caller), pageNumber, size, MAX_PAGE_SIZE);
        }
        String text = ToolArguments.requireText("query", query, ToolArguments.MAX_QUERY_LENGTH);
        DictionaryPage result = search(text, pageNumber, size, caller);
        return ConceptSearchResult
            .ofQuery(text, pageNumber, size, result.total(), result.concepts().stream().map(ConceptSummary::summary).toList());
    }

    private static List<String> validTerms(List<String> terms) {
        if (terms.size() > MAX_TERMS) {
            throw new ToolFailure("Argument 'terms' takes at most " + MAX_TERMS + " terms.");
        }
        List<String> valid = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            valid.add(ToolArguments.requireText("terms[" + i + "]", terms.get(i), ToolArguments.MAX_QUERY_LENGTH));
        }
        return valid.stream().distinct().toList();
    }

    private DictionaryPage search(String text, int pageNumber, int size, CallerHeaders caller) {
        return DictionaryCalls.run(DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConcepts(text, pageNumber, size, caller));
    }

    private List<TermPages.TermPage> searchAll(List<String> terms, int pageNumber, int size, CallerHeaders caller) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        try (ExecutorService pool = Executors.newFixedThreadPool(terms.size(), Thread.ofVirtual().name("search-term-", 0).factory())) {
            List<Future<DictionaryPage>> pages =
                terms.stream().map(term -> pool.submit(() -> withMdc(mdc, () -> search(term, pageNumber, size, caller)))).toList();
            List<TermPages.TermPage> outcomes = new ArrayList<>();
            for (int i = 0; i < terms.size(); i++) {
                outcomes.add(outcome(terms.get(i), pages.get(i)));
            }
            return outcomes;
        }
    }

    private static TermPages.TermPage outcome(String term, Future<DictionaryPage> page) {
        try {
            return TermPages.TermPage.found(term, page.get());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ToolFailure failure) {
                return TermPages.TermPage.failed(term, failure);
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ToolFailure.unavailable();
        }
    }

    private static DictionaryPage withMdc(Map<String, String> mdc, Supplier<DictionaryPage> call) {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            return call.get();
        } finally {
            MDC.clear();
        }
    }
}
