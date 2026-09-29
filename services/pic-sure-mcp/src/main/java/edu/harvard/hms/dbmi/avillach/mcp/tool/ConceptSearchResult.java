package edu.harvard.hms.dbmi.avillach.mcp.tool;

import java.util.List;

/**
 * One page of concept search results.
 *
 * @param query the search text
 * @param page the zero-based page number
 * @param pageSize the page size used
 * @param total how many concepts match in all
 * @param concepts the concepts on this page
 */
public record ConceptSearchResult(String query, int page, int pageSize, long total, List<ConceptSummary> concepts) {
}
