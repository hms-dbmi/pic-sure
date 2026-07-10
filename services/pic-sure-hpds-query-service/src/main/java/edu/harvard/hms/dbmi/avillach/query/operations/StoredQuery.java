package edu.harvard.hms.dbmi.avillach.query.operations;

import java.util.UUID;

/**
 * Public JSON shape returned by {@code GET /internal/queries/{picsureId}} on pic-sure-operations-service. Mirrors
 * {@code edu.harvard.hms.dbmi.avillach.operations.query.StoredQuery} byte-for-byte. {@code status} is the
 * {@code edu.harvard.dbmi.avillach.domain.PicSureStatus} enum NAME (or {@code null} if unset). {@code metadata} is base64-encoded bytes (or
 * {@code null} if unset).
 */
public record StoredQuery(UUID picsureId, String query, String resourceResultId, String status, String version, String metadata) {
}
