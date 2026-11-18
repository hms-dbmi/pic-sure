package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.List;
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
 *
 * <p>{@code queries} lists the query rows the caller owns under the dataset's general query, each with its variant: the same query in every
 * combination of sample flag and result type the caller has submitted. It is empty for a dataset that has no general query. A dataset on a
 * query row with no owner has one once {@link DatasetBringOver} has filed it, and then lists the rows the caller stored under it, never the
 * ownerless row itself. The nested {@code query} is the row {@code named_dataset.queryId} names.
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
    ) Map<String, Object> metadata,
    @Schema(
        description = "The caller's stored variants of the query the dataset points at: that query as the caller submitted it with each sample flag and result type, ordered by sample flag, then result type, then query id. The variants are found through a general query. When the caller owns the query the query member holds, the list is the caller's queries under the general query of that query, that query included. A query stored without an owner, which is what a dataset saved before queries had owners points at, is under no general query and is never listed itself. Where the deployment turns it on, a read by the dataset's user files such a dataset under the general query derived for that user from the stored query, and the list then holds the variants the caller has stored by submitting that query. The list is empty until the dataset is filed that way, when it is filed under the general query of another user, when the query the dataset points at belongs to another user, and when the caller has stored no variant. Clients written before this member existed ignore it.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<LinkedQueryDto> queries
) {
}
