package edu.harvard.dbmi.avillach.contracts.internal;


/**
 * Body of {@code GET /internal/queries/{picsureId}/dispatch}: the stored query re-serialized for execution, with credentials stripped.
 *
 * <p>The key name and the fact that its value is a JSON STRING rather than a nested object are both load-bearing -- the gateway's
 * query-auth fetcher already parses exactly this shape.
 */
public record DispatchResponse(String queryJson) {
}
