package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Create and update request body for {@code /dataset/named/**}. The {@code queryId} is resolved to a persisted {@code Query} by the
 * service; it is not a column on {@code NamedDataset} itself.
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
