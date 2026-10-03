package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * One page of concept search results. A search by {@code search} sets {@code search} and leaves {@code terms}, {@code truncated}, and
 * {@code warnings} out. A search by {@code terms} sets those three and leaves {@code search} out. Fields that do not apply are omitted from
 * the JSON, since Spring AI rejects a null in {@code structuredContent}.
 *
 * @param search the search text of a single search
 * @param terms the terms of a several-term search, in the order given
 * @param page the zero-based page number, for a several-term search the page asked of each term
 * @param pageSize the page size used, for a several-term search the page size asked of each term
 * @param total how many concepts match in all, for a several-term search the sum of the per-term totals
 * @param concepts the concepts on this page, for a several-term search the merged concepts
 * @param truncated whether a several-term search matched more concepts than {@link ConceptSearchTool#MAX_PAGE_SIZE} on its pages
 * @param warnings one entry per term whose search failed, naming the term and the failure
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConceptSearchResult(
    @Schema(requiredMode = NOT_REQUIRED, description = SEARCH_DESCRIPTION) String search,
    @Schema(requiredMode = NOT_REQUIRED, description = TERMS_DESCRIPTION) List<String> terms, int page, int pageSize, long total,
    List<ConceptSummary> concepts, @Schema(requiredMode = NOT_REQUIRED, description = TRUNCATED_DESCRIPTION) Boolean truncated,
    @Schema(requiredMode = NOT_REQUIRED, description = WARNINGS_DESCRIPTION) List<String> warnings
) {

    /** The schema description of {@code search}. */
    public static final String SEARCH_DESCRIPTION = "The search text of a single search; absent for a search by terms";

    /** The schema description of {@code terms}. */
    public static final String TERMS_DESCRIPTION = "The terms of a search by terms, in the order given; absent for a single search";

    /** The schema description of {@code truncated}, which tells the model the merged list is not every match. */
    public static final String TRUNCATED_DESCRIPTION = "True when a search by terms matched more than " + ConceptSearchTool.MAX_PAGE_SIZE
        + " distinct concepts on the pages asked for and only the first " + ConceptSearchTool.MAX_PAGE_SIZE
        + " were kept; absent for a single search";

    /** The schema description of {@code warnings}, which tells the model a failed term was not searched. */
    public static final String WARNINGS_DESCRIPTION =
        "One entry per term whose search failed, naming the term and the failure; those terms returned no concepts. Absent when no term failed";

    /**
     * The result of a single search by {@code search}.
     *
     * @param search the search text
     * @param page the zero-based page number
     * @param pageSize the page size used
     * @param total how many concepts match in all
     * @param concepts the concepts on this page
     * @return the result, with no terms, truncated flag, or warnings
     */
    public static ConceptSearchResult ofSearch(String search, int page, int pageSize, long total, List<ConceptSummary> concepts) {
        return new ConceptSearchResult(search, null, page, pageSize, total, concepts, null, null);
    }
}
