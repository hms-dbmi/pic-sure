package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.Map;
import java.util.UUID;

/**
 * Public JSON shape for a {@code NamedDataset}, mirroring the legacy {@code NamedDatasetRS} wire shape. Kept separate from the
 * {@code pic-sure-api-data} JPA entity so the persistence model (and the gzip-compressed {@code Query} blob it references) never leaks
 * directly onto the wire -- the referenced query is projected through {@link NamedDatasetQueryDto} instead.
 *
 * <p>The nested {@code query} object (rather than a flat {@code queryId}) is load-bearing: the frontend's {@code mapDataset()} reads
 * {@code query.query}, {@code query.uuid}, {@code query.startTime} and {@code query.status} off it, and derives its own {@code queryId}
 * from {@code query.uuid}. Flattening it makes the Manage Datasets page render "API Error" on an otherwise-200 response.
 */
public record NamedDatasetDto(
    UUID uuid, String user, String name, NamedDatasetQueryDto query, Boolean archived, Map<String, Object> metadata
) {
}
