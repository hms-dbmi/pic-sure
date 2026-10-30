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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MCP tool that searches the PIC-SURE data dictionary. */
@Component
public class ConceptSearchTool {

    /** Page size used when the model sends none. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** Largest page size the tool honors. */
    public static final int MAX_PAGE_SIZE = 50;

    /** Most terms one call may search. */
    public static final int MAX_TERMS = 5;

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
     * Searches concepts by free text and returns a trimmed page the model can read. At most one of {@code search} and {@code terms} may be
     * given, and neither only with {@code facets}, which browses. With {@code terms}, the dictionary searches for any of them in one request
     * and says which terms match each concept.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param search the free-text search, all of whose words must match
     * @param terms up to {@value #MAX_TERMS} separate searches, such as synonyms and abbreviations
     * @param page the zero-based page number, default 0
     * @param pageSize the results per page, default 10, at most 50
     * @param facets the facets to narrow the search by, as {@code list_facets} returned them; none by default
     * @return the page of concepts
     * @throws ToolFailure with a model-facing message for a bad argument or a failed dictionary call
     */
    @AuditEvent(type = "SEARCH", action = "concept.search")
    @McpTool(
        name = "search_concepts",
        description = """
            Search the PIC-SURE data dictionary for variables (concepts) by free text, for example \
            "systolic blood pressure" or "sex". Every word of a search must match, so a multi-word search must describe one \
            concept: "blood pressure hypertension" finds nothing, and "blood pressure" misses concepts named "BP". Matching is \
            on the text, so an abbreviation such as "BMI" can return no results while "body mass index" matches. Search synonyms \
            and abbreviations as separate terms, which the terms argument does in one call. Give either search or terms (up to 5), \
            not both. With terms, each concept lists the matchedTerms that returned it, total counts the concepts that match any term, \
            and termTotals gives how many concepts each term matches alone: a term at 0 matched nothing, so retry it at most once \
            with synonyms and otherwise report it as not found. Paging applies to the merged results. Returns \
            open-access dictionary metadata only, never participant data. Each result has a conceptPath, the identifier \
            get_concept, the count tools, and get_adapter_code take. display and dataset are left out when they equal the \
            conceptPath's last and first segments. Categorical concepts list up to 20 values and report valuesOmitted when there \
            are more, and continuous concepts give min and max. Results are paged, 10 per page by default and at most 50; pass pageSize 25 unless you have a reason not to, \
            and request the next page when total exceeds what you have. To narrow a search to a study, dataset or data type, \
            pass facets, a list of {category, name} pairs copied exactly from list_facets; a facet narrows every term in the search. With facets, search and terms may be left out to browse everything in the facet; never invent a term or send "*" just to fill it.""", generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "Search concepts", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public ConceptSearchResult searchConcepts(
        McpTransportContext context, @McpToolParam(
            description = "Free-text search; every word must match. Give search or terms, not both. May be left out only when facets are given", required = false
        ) String search, @McpToolParam(
            description = "Up to 5 separate searches, such as synonyms and abbreviations, run in one call. Give search or terms, not both",
            required = false
        ) List<String> terms, @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
        @McpToolParam(description = "Results per page, default 10, max 50; use 25", required = false) Integer pageSize,
        @McpToolParam(
            description = "Facets to narrow by: {category, name} pairs copied exactly from list_facets; omit for none", required = false
        ) List<FacetFilter> facets
    ) {
        boolean hasSearch = search != null && !search.isBlank();
        boolean hasTerms = terms != null && !terms.isEmpty();
        if (hasSearch && hasTerms) {
            throw new ToolFailure("Give either 'search' or 'terms', not both.");
        }
        if (!hasSearch && !hasTerms && (facets == null || facets.isEmpty())) {
            throw new ToolFailure("Give 'search', 'terms', or 'facets'.");
        }
        List<FacetFilter> narrowing = checkedFacets(facets);
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        CallerHeaders caller = CallerHeaders.from(context);
        if (hasTerms) {
            List<String> searchTerms = validTerms(terms);
            DictionaryPage result = DictionaryCalls.run(
                DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConceptTerms(searchTerms, narrowing, pageNumber, size, caller)
            );
            return termsResult(searchTerms, result, pageNumber, size);
        }
        // A facet alone is a browse: the dictionary takes an empty search, as the UI sends when only a facet is picked.
        String text = hasSearch ? ToolArguments.requireText("search", search, ToolArguments.MAX_SEARCH_LENGTH) : "";
        DictionaryPage result = search(text, narrowing, pageNumber, size, caller);
        return ConceptSearchResult
            .ofSearch(text, pageNumber, size, result.total(), result.concepts().stream().map(ConceptSummary::summary).toList());
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
     * Checks the terms. They are joined with {@code |} into one search the dictionary cuts at {@link ToolArguments#MAX_SEARCH_LENGTH}
     * characters, so a {@code |} inside a term would split it, and a joined search over the cut would lose its last terms.
     */
    private static List<String> validTerms(List<String> terms) {
        if (terms.size() > MAX_TERMS) {
            throw new ToolFailure("Argument 'terms' takes at most " + MAX_TERMS + " terms.");
        }
        List<String> valid = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            String term = ToolArguments.requireText("terms[" + i + "]", terms.get(i), ToolArguments.MAX_SEARCH_LENGTH);
            if (term.indexOf('|') >= 0) {
                throw new ToolFailure("Argument 'terms[" + i + "]' must not contain '|'; give each alternative as its own term.");
            }
            valid.add(term);
        }
        List<String> distinct = valid.stream().distinct().toList();
        if (String.join("|", distinct).length() > ToolArguments.MAX_SEARCH_LENGTH) {
            throw new ToolFailure("Argument 'terms' must total at most " + ToolArguments.MAX_SEARCH_LENGTH + " characters.");
        }
        return distinct;
    }

    private DictionaryPage search(String text, List<FacetFilter> facets, int pageNumber, int size, CallerHeaders caller) {
        return DictionaryCalls.run(DictionaryClient.CONCEPTS_PATH, () -> dictionary.searchConcepts(text, facets, pageNumber, size, caller));
    }

    private static ConceptSearchResult termsResult(List<String> searched, DictionaryPage result, int pageNumber, int size) {
        // A dictionary without the breakdown sends no matches; its concepts then carry no matchedTerms rather than an empty list.
        List<ConceptSummary> concepts;
        if (result.matches() == null) {
            concepts = result.concepts().stream().map(ConceptSummary::summary).toList();
        } else {
            Map<List<String>, List<String>> matched = new HashMap<>();
            result.matches().stream().filter(m -> m != null && m.terms() != null)
                .forEach(m -> matched.put(List.of(String.valueOf(m.dataset()), String.valueOf(m.conceptPath())), m.terms()));
            concepts = result.concepts().stream().map(
                concept -> ConceptSummary.summary(concept).withMatchedTerms(
                    matched.getOrDefault(List.of(String.valueOf(concept.dataset()), String.valueOf(concept.conceptPath())), List.of())
                )
            ).toList();
        }
        Map<String, Long> totals = result.termTotals();
        // The dictionary reports the terms it searched, which are the given terms with punctuation stripped.
        List<String> terms = totals == null || totals.isEmpty() ? searched : List.copyOf(totals.keySet());
        return new ConceptSearchResult(null, terms, pageNumber, size, result.total(), concepts, totals == null || totals.isEmpty() ? null : totals);
    }
}
