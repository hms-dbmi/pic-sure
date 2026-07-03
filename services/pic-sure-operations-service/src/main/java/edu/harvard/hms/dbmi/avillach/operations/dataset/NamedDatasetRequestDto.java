package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Create/update request body for {@code /dataset/named/**}. Mirrors {@code pic-sure-api-data}'s javax-validated {@code NamedDatasetRequest}
 * verbatim (the {@code @Pattern} on {@code name} is copied byte-for-byte), in the jakarta namespace, so this service stays self-contained.
 * {@code queryId} is resolved to a persisted {@code Query} by the service -- it is not a column on {@code NamedDataset} itself.
 */
public record NamedDatasetRequestDto(
    @NotNull UUID queryId, @NotNull @Pattern(regexp = "^[\\w\\d \\-\\\\/?+=\\[\\].():\"']+$") String name, Boolean archived,
    Map<String, Object> metadata
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
