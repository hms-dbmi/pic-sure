package edu.harvard.dbmi.avillach.dictionary.concept.model;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an export writes about one concept: the same display, dataset and description the concept detail endpoints return, and the requested
 * metadata under the keys the dictionary stores, with no key hidden and no key reworded.
 *
 * @param conceptPath the concept's path
 * @param display the concept's display name
 * @param dataset the {@code ref} of the dataset the concept belongs to
 * @param description the concept's {@code description} metadata value, null when it has none
 * @param meta the requested metadata the concept has, keyed by raw metadata key
 */
@Schema(description = "The dictionary columns and raw metadata an export writes for one concept.")
public record ConceptExportMetadata(
    @Schema(
        description = "The concept's path.", example = "\\Synthea\\ACT Demographics\\Sex\\", requiredMode = Schema.RequiredMode.REQUIRED
    ) String conceptPath, @Schema(description = "The concept's display name.", example = "Sex") String display,
    @Schema(description = "Identifier of the dataset the concept belongs to.", example = "Synthea") String dataset,
    @Schema(description = "The concept's description. Null when it has none.", example = "Sex of the participant") String description,
    @Schema(
        description = "The requested metadata the concept has, keyed by the metadata key as stored, such as drs_uri. Empty when the "
            + "concept has none of the requested keys."
    ) Map<String, String> meta
) {
}
