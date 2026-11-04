package edu.harvard.dbmi.avillach.dictionary.dataset;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.Map;

@Schema(description = "A dataset, usually one study, that concepts belong to.")
public record Dataset(
    @Schema(
        description = "Identifier of the dataset. For a dbGaP study this is its accession.", example = "phs000007",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String ref,
    @Schema(
        description = "Full name of the study.", example = "Framingham Cohort", requiredMode = Schema.RequiredMode.REQUIRED
    ) String fullName,
    @Schema(description = "Short name of the study.", example = "FHS", requiredMode = Schema.RequiredMode.REQUIRED) String abbreviation,
    @Schema(
        description = "Summary of the study.",
        example = "The Framingham Heart Study follows cardiovascular disease across three generations of participants."
    ) String description,
    @Schema(
        description = "Dataset metadata from the dictionary. The keys are the dataset's metadata keys rewritten as capitalized words, such as Study Focus and Study Design, and vary by deployment. Null when the metadata was not loaded."
    ) @Nullable Map<String, String> meta
) {

    public Dataset(String ref, String fullName, String abbreviation, String description) {
        this(ref, fullName, abbreviation, description, null);
    }

    public Dataset withMeta(Map<String, String> meta) {
        return new Dataset(ref, fullName, abbreviation, description, meta);
    }
}
