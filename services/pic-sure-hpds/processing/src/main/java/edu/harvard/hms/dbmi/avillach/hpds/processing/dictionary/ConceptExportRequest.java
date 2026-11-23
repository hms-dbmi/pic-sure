package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import java.util.List;

/**
 * The body HPDS posts to the dictionary service's internal export metadata endpoint.
 *
 * @param conceptPaths the concept paths to look up
 * @param metaKeys the raw metadata keys to return for each concept, which the dictionary checks against its own allowlist
 */
public record ConceptExportRequest(List<String> conceptPaths, List<String> metaKeys) {
}
