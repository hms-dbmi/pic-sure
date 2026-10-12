package edu.harvard.dbmi.avillach.dictionary.concept.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Which terms of a search returned one concept of a page.
 *
 * @param dataset the dataset of the concept, which with the path identifies it
 * @param conceptPath the complete path of the concept
 * @param terms the terms of the search that match the concept, in the order the search gave them
 */
@Schema(description = "The terms of the search that return one concept of the page. The dataset and path identify the concept.")
public record ConceptMatch(
    @Schema(description = "The dataset the concept belongs to.", example = "phs000007", requiredMode = Schema.RequiredMode.REQUIRED) String dataset,
    @Schema(description = "The complete concept path.", example = "\\phs000007\\age\\", requiredMode = Schema.RequiredMode.REQUIRED) String conceptPath,
    @Schema(
        description = "The terms of the search that match this concept, in the order the search gave them.",
        example = "[\"blood pressure\", \"bp\"]", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<String> terms
) {
}
