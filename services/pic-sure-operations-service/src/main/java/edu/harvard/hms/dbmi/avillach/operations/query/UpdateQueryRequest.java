package edu.harvard.hms.dbmi.avillach.operations.query;

/**
 * Request body for {@code PATCH /internal/queries/{picsureId}}: every field is nullable and means "leave unchanged" when absent, so callers
 * can update just the one field they care about (typically {@code status} as a dispatch completes, or {@code resourceResultId} once HPDS
 * assigns one) without re-sending the whole row. {@code status} is the {@link edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus}
 * enum NAME; {@code metadata} is base64-encoded bytes.
 */
public record UpdateQueryRequest(String status, String resourceResultId, String metadata) {
}
