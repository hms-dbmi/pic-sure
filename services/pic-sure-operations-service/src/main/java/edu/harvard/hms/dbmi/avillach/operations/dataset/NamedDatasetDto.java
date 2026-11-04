package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Public JSON shape for a {@code NamedDataset}. Kept separate from the JPA entity so the persistence model and its gzip-compressed
 * {@code Query} blob never leak directly onto the wire; the referenced query is projected through {@link NamedDatasetQueryDto}.
 *
 * <p>The nested {@code query} object (rather than a flat {@code queryId}) is load-bearing: the frontend's {@code mapDataset()} reads
 * {@code query.query}, {@code query.uuid}, {@code query.startTime} and {@code query.status} off it, and derives its own {@code queryId}
 * from {@code query.uuid}. Flattening it makes the Manage Datasets page render "API Error" on an otherwise-200 response.
 */
@Schema(description = "A saved query that a user has named, with the query it points at.")
public record NamedDatasetDto(
    @Schema(
        description = "The id of the named dataset.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The email of the owner. Only the owner can read or change the dataset.", example = "researcher@example.org",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String user, @Schema(description = "The name the owner gave the dataset.", example = "Hypertension cohort 2026") String name,
    @Schema(
        description = "The query the dataset points at. The response has no flat queryId: clients read the id from this object.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) NamedDatasetQueryDto query,
    @Schema(
        description = "Whether the owner archived the dataset. Archived datasets are still listed.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Boolean archived,
    @Schema(
        description = "Free-form settings the client stored with the dataset, returned unchanged. The service defines no keys of its own: the keys are whatever the client sent."
    ) Map<String, Object> metadata
) {
}
