package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Create and update request body for {@code /dataset/named/**}. The {@code queryId} is resolved to a persisted {@code Query} by the
 * service; it is not a column on {@code NamedDataset} itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "The fields of a named dataset to create or replace.")
public record NamedDatasetRequestDto(
    @Schema(
        description = "The PIC-SURE id of the query to name. It must identify a persisted query.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID queryId,
    @Schema(
        description = "The name for the dataset. Letters, digits, spaces and the punctuation the pattern allows.",
        example = "Hypertension cohort 2026", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull @Pattern(regexp = "^[\\w\\d \\-\\\\/?+=\\[\\].():\"']+$") String name,
    @Schema(description = "Whether the dataset is archived. False when absent.") Boolean archived,
    @Schema(
        description = "Free-form settings to store with the dataset. An empty object when absent. The service defines no keys of its own: it stores whatever keys the client sends."
    ) Map<String, Object> metadata
) {
    public NamedDatasetRequestDto {
        if (archived == null) {
            archived = false;
        }
        if (metadata == null) {
            metadata = new HashMap<>();
        }
    }
}
