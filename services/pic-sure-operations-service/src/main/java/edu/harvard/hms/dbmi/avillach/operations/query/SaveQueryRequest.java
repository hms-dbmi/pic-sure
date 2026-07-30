package edu.harvard.hms.dbmi.avillach.operations.query;

/**
 * Request body for {@code POST /internal/queries}: the caller (hpds-query-service) hands over the full query it wants persisted.
 * {@code status} is the {@link edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus} enum NAME (e.g. {@code "QUEUED"}), not its
 * ordinal -- kept as a plain string on the wire so callers never need the enum type. {@code metadata} is base64-encoded bytes (mirrors the
 * {@code Query} entity's {@code byte[] metadata} column), or {@code null} when there is none.
 */
public record SaveQueryRequest(String query, String resourceResultId, String status, String version, String metadata) {
}
