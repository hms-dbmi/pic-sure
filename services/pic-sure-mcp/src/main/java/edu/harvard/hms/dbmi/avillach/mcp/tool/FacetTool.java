package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.FacetCategory;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** MCP tool that lists the dictionary's facet categories and counts for a search. */
@Component
public class FacetTool {

    /** Most facet categories a result carries. */
    public static final int MAX_CATEGORIES = 25;

    /** Most facets a result carries per category. */
    public static final int MAX_FACETS_PER_CATEGORY = 25;

    /** Longest {@code category} or {@code match} argument accepted, in characters. */
    public static final int MAX_FILTER_LENGTH = 500;

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
     * Lists facet categories and counts for a search, capped so one call cannot fill the client's context. {@code category} and
     * {@code match} narrow the result before the cap applies, so a facet the cap dropped can still be looked up.
     *
     * @param context the MCP transport context carrying the caller's headers
     * @param search the free-text search, or null for all concepts
     * @param category the one category to list, by name, or null for all
     * @param match text a facet's name or display name must contain, ignoring case, or null for every facet
     * @return the capped facet categories
     * @throws ToolFailure with a model-facing message for a bad argument or a failed dictionary call
     */
    @AuditEvent(type = "SEARCH", action = "facet.search")
    @McpTool(
        name = "list_facets", description = """
            List the PIC-SURE data dictionary's facet categories, such as study or data type, with the number of \
            concepts each facet matches for an optional free-text search. Returns open-access dictionary metadata only, \
            never participant data. Use it to see how a search spreads across studies before calling search_concepts. \
            At most 25 categories and 25 facets per category come back, and categoriesOmitted and facetsOmitted say how many \
            were dropped; to find a facet that was dropped, call again with category and match. A facet with a parent is \
            nested under that facet and is selected the same way, by its category and name. The dictionary ANDs every word \
            of the search with prefix matching, so a multi-word search must describe one concept. Search synonyms and \
            abbreviations as separate terms; search_concepts takes several in one call with its terms argument.""",
        generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            title = "List facets", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false
        )
    )
    public FacetResult listFacets(
        McpTransportContext context,
        @McpToolParam(
            description = "Free-text search of at most " + ToolArguments.MAX_SEARCH_LENGTH
                + " characters, or empty for all concepts; punctuation is ignored and splits words",
            required = false
        ) String search,
        @McpToolParam(
            description = "Only this category, by its name as list_facets returned it; omit for all", required = false
        ) String category,
        @McpToolParam(
            description = "Only facets whose name or display name contains this text, ignoring case; omit for all", required = false
        ) String match
    ) {
        String text = ToolArguments.optionalText("search", search, ToolArguments.MAX_SEARCH_LENGTH);
        String onlyCategory = ToolArguments.optionalText("category", category, MAX_FILTER_LENGTH);
        String needle = ToolArguments.optionalText("match", match, MAX_FILTER_LENGTH).strip().toLowerCase(Locale.ROOT);
        List<FacetResult.Category> categories =
            DictionaryCalls.run(DictionaryClient.FACETS_PATH, () -> dictionary.listFacets(text, CallerHeaders.from(context))).stream()
                .filter(Objects::nonNull).filter(c -> onlyCategory.isEmpty() || onlyCategory.equals(c.name())).map(c -> category(c, needle))
                .filter(c -> needle.isEmpty() || !c.facets().isEmpty()).toList();
        List<FacetResult.Category> kept = categories.stream().limit(MAX_CATEGORIES).toList();
        Integer omitted = categories.size() > MAX_CATEGORIES ? categories.size() - MAX_CATEGORIES : null;
        return new FacetResult(text, kept, omitted);
    }

    private static FacetResult.Category category(FacetCategory category, String needle) {
        List<FacetResult.Facet> facets = new ArrayList<>();
        flatten(category.facets(), null, facets);
        List<FacetResult.Facet> matching = needle.isEmpty() ? facets : facets.stream().filter(f -> matches(f, needle)).toList();
        List<FacetResult.Facet> kept = matching.stream().limit(MAX_FACETS_PER_CATEGORY).toList();
        Integer omitted = matching.size() > MAX_FACETS_PER_CATEGORY ? matching.size() - MAX_FACETS_PER_CATEGORY : null;
        return new FacetResult.Category(category.name(), category.display(), category.description(), kept, omitted);
    }

    /** Lists each facet followed by the facets nested under it, so nested facets are as visible (and selectable) as top-level ones. */
    private static void flatten(List<FacetCategory.Facet> facets, String parent, List<FacetResult.Facet> into) {
        if (facets == null) {
            return;
        }
        for (FacetCategory.Facet facet : facets) {
            if (facet != null) {
                into.add(new FacetResult.Facet(facet.name(), facet.display(), facet.count(), parent));
                flatten(facet.children(), facet.name(), into);
            }
        }
    }

    private static boolean matches(FacetResult.Facet facet, String needle) {
        return contains(facet.name(), needle) || contains(facet.display(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
