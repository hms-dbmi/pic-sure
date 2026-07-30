package edu.harvard.dbmi.avillach.contracts.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code GET /internal/queries/{picsureId}/dispatch}: the stored query re-serialized for execution, with credentials stripped.
 *
 * <p>The key name and the fact that its value is a JSON STRING rather than a nested object are both load-bearing -- the gateway's
 * query-auth fetcher already parses exactly this shape.
 */
@Schema(description = "The stored query, ready to dispatch, with resource credentials stripped")
public record DispatchResponse(
    @Schema(description = "Bare v3 Query JSON as a STRING (never a nested object); null when the stored query was blank") String queryJson
) {
}
