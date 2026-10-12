package edu.harvard.hms.dbmi.avillach.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * One page of concept search results.
 *
 * @param page the zero-based page number
 * @param total how many concepts match in all
 * @param concepts the concepts on this page
 * @param termTotals for a search with several |-separated terms, how many concepts each term matches on its own, in the order given;
 *        null for a single-term search
 */
public record ConceptSearchResult(
    int page, long total, List<ConceptSummary> concepts, @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(requiredMode = NOT_REQUIRED) Map<String, Long> termTotals
) {

    public ConceptSearchResult(int page, long total, List<ConceptSummary> concepts) {
        this(page, total, concepts, null);
    }
}
