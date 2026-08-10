package edu.harvard.dbmi.avillach.contracts.internal;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Body of {@code GET /internal/queries/{picsureId}}: the full persisted row minus the entity/blob plumbing.
 *
 * <p>Deliberately distinct from {@link DispatchResponse}, which carries only the (auth-mutated) query body.
 */
@Schema(description = "A query as persisted by the internal query store")
public record StoredQuery(
    @Schema(description = "PIC-SURE-wide id of this query") UUID picsureId,
    @Schema(description = "Canonical bare v3 Query JSON; opaque to the store") String query,
    @Schema(description = "Result id assigned by the backing resource; null until the query has been dispatched") String resourceResultId,
    @Schema(
        description = "PIC-SURE status; travels as the enum NAME, never its ordinal", allowableValues = {
            "QUEUED", "PENDING", "ERROR", "AVAILABLE"}
    ) PicSureStatus status, @Schema(description = "Query-format version this query was written against, e.g. \"v3\"") String version,
    @Schema(description = "base64-encoded metadata bytes; callers that need the JSON therein decode it themselves") String metadata,
    @Schema(description = "Epoch millis when the row was saved; server-owned, null if unset", example = "1785873600000") Long startTime,
    @Schema(
        description = "Epoch millis of the first transition to AVAILABLE; server-owned, null until then", example = "1785873612000"
    ) Long readyTime
){

    /**
     * Convenience for callers that carry no timing. {@code startTime}/{@code readyTime} are server-owned, so only the store itself has a
     * reason to set them.
     */
    public StoredQuery(UUID picsureId, String query, String resourceResultId, PicSureStatus status, String version, String metadata) {
        this(picsureId, query, resourceResultId, status, version, metadata, null, null);
    }
}
