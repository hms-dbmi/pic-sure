package edu.harvard.hms.dbmi.avillach.mcp.tool;

import java.util.List;

/**
 * The concepts a batch detail lookup found, and the requested paths it did not.
 *
 * @param concepts the concepts found, trimmed as a {@code get_concept} result is, in the order the dictionary returned them
 * @param notFound the requested concept paths the dictionary did not return, in the order they were requested, empty when all were found
 */
public record ConceptsDetailResult(List<ConceptSummary> concepts, List<String> notFound) {
}
