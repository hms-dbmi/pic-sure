package edu.harvard.dbmi.avillach.dictionary.legacysearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A concept in the legacy search shape.")
public record Result(
    @Schema(
        description = "The concept's attributes under their legacy column names. A continuous concept also carries min and max.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Metadata metadata,
    @Schema(
        description = "The categories of a categorical concept. Empty for a continuous concept.", example = "[\"Male\", \"Female\"]",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<String> values,
    @Schema(
        description = "Ref of the dataset the concept belongs to.", example = "phs000007", requiredMode = Schema.RequiredMode.REQUIRED
    ) @JsonProperty("studyId") String studyId,
    @Schema(
        description = "Name of the concept's parent concept, or All Variables when it has none.", example = "demographics",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @JsonProperty("dtId") String dtId,
    @Schema(
        description = "Name of the concept, the last segment of its path.", example = "AGE", requiredMode = Schema.RequiredMode.REQUIRED
    ) @JsonProperty("varId") String varId,
    @Schema(description = "True for a categorical concept.", requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty(
        "is_categorical"
    ) boolean isCategorical,
    @Schema(description = "True for a continuous concept.", requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty(
        "is_continuous"
    ) boolean isContinuous
) {
}
