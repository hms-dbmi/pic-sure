package edu.harvard.hms.dbmi.avillach.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * One page of concept search results. A search by {@code search} sets {@code search} and leaves {@code terms} and {@code termTotals} out. A search by {@code terms} sets those two and leaves {@code search} out. Fields that do not apply are omitted from
 * the JSON, since Spring AI rejects a null in {@code structuredContent}.
 *
 * @param search the search text of a single search
 * @param terms the terms of a several-term search, in the order given
 * @param page the zero-based page number, for a several-term search the page asked of the merged results
 * @param pageSize the page size used, for a several-term search the page size asked of the merged results
 * @param total how many concepts match in all, for a several-term search the number of concepts that match any term
 * @param concepts the concepts on this page, for a several-term search each names the terms that match it
 * @param termTotals for a several-term search, how many concepts each term matches on its own, in the order given
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConceptSearchResult(
    @Schema(requiredMode = NOT_REQUIRED) String search, @Schema(requiredMode = NOT_REQUIRED) List<String> terms, int page, int pageSize,
    long total, List<ConceptSummary> concepts, @Schema(requiredMode = NOT_REQUIRED) Map<String, Long> termTotals
) {

    /**
     * The result of a single search by {@code search}.
     *
     * @param search the search text
     * @param page the zero-based page number
     * @param pageSize the page size used
     * @param total how many concepts match in all
     * @param concepts the concepts on this page
     * @return the result, with no terms or term totals
     */
    public static ConceptSearchResult ofSearch(String search, int page, int pageSize, long total, List<ConceptSummary> concepts) {
        return new ConceptSearchResult(search, null, page, pageSize, total, concepts, null);
    }
}
