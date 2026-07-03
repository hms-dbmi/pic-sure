package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;

/**
 * Public JSON shape returned by {@code GET /internal/queries/{picsureId}} and {@code GET
 * /internal/queries/by-common-area/{commonAreaUUID}}: the full persisted {@code Query} row, minus the gzip-blob/entity plumbing.
 * {@code status} is the {@link edu.harvard.dbmi.avillach.domain.PicSureStatus} enum NAME (or {@code null} if unset). {@code metadata} is
 * base64-encoded bytes (or {@code null} if unset) -- callers that need the raw JSON therein (e.g. {@code commonAreaUUID}) decode it
 * themselves.
 *
 * <p>Deliberately distinct from the gateway-only dispatch payload ({@code {queryJson: "..."}}), which excludes everything here except the
 * (auth-mutated) query body -- see {@code InternalQueryController#dispatch}.
 */
public record StoredQuery(UUID picsureId, String query, String resourceResultId, String status, String version, String metadata) {
}
