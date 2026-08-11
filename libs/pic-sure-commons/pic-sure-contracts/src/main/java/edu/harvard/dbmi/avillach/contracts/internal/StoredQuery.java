package edu.harvard.dbmi.avillach.contracts.internal;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;

import java.util.UUID;

/**
 * Body of {@code GET /internal/queries/{picsureId}}: the full persisted row minus the entity/blob plumbing.
 *
 * <p>Deliberately distinct from {@link DispatchResponse}, which carries only the (auth-mutated) query body.
 */
public record StoredQuery(
    UUID picsureId, String query, String resourceResultId, PicSureStatus status, String version, String metadata, Long startTime,
    Long readyTime
) {

    /**
     * Convenience for callers that carry no timing. {@code startTime}/{@code readyTime} are server-owned, so only the store itself has a
     * reason to set them.
     */
    public StoredQuery(UUID picsureId, String query, String resourceResultId, PicSureStatus status, String version, String metadata) {
        this(picsureId, query, resourceResultId, status, version, metadata, null, null);
    }
}
