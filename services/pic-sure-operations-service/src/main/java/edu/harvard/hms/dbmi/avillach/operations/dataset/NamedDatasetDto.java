package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.Map;
import java.util.UUID;

/**
 * Public JSON shape for a {@code NamedDataset}, mirroring the legacy {@code NamedDatasetRS} wire shape. Kept separate from the
 * {@code pic-sure-api-data} JPA entity so the persistence model (and the gzip-compressed {@code Query} blob it references) never leaks
 * directly onto the wire -- {@code queryId} is the referenced {@code Query}'s uuid, not the query body itself.
 */
public record NamedDatasetDto(UUID uuid, String name, UUID queryId, Boolean archived, Map<String, Object> metadata) {
}
