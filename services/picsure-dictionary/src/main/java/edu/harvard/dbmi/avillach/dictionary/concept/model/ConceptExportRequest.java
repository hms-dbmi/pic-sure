package edu.harvard.dbmi.avillach.dictionary.concept.model;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of the internal export metadata lookup: the concepts an export covers and the raw metadata keys it needs for each.
 *
 * @param conceptPaths the concept paths to look up, at most 5000
 * @param metaKeys the raw {@code concept_node_meta} keys to return, each of which must be in {@code metadata.internal_export_keys}
 */
@Schema(description = "The concepts an export covers and the raw metadata keys it needs for each of them.")
public record ConceptExportRequest(
    @Schema(
        description = "The concept paths to look up, at most 5000. A path that matches no concept is left out of the response.",
        example = "[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\"]", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<String> conceptPaths,
    @Schema(
        description = "The metadata keys to return for each concept, as stored. Each must be one the deployment allows for export.",
        example = "[\"drs_uri\"]", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<String> metaKeys
) {
}
