package edu.harvard.hms.dbmi.avillach.conventions.schemafixtures.model;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Members with no description: no annotation at all, an example alone, a blank description, and three
 * members whose types are exempt from the example.
 *
 * @param bare no annotation
 * @param exampleOnly an example and no description
 * @param blank a blank description
 * @param nested a nested model with no annotation
 * @param attributes a map with no annotation
 * @param flag a boolean with no annotation
 */
@Schema(description = "A model with undescribed members")
public record MissingFieldDescriptions(
    String bare,
    @Schema(example = "phs000007") String exampleOnly,
    @Schema(description = " ", example = "phs000007") String blank,
    Investigator nested,
    Map<String, String> attributes,
    boolean flag
) {}
