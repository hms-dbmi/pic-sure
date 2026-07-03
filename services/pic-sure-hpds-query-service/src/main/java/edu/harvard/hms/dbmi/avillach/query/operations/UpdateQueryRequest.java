package edu.harvard.hms.dbmi.avillach.query.operations;

/**
 * Request body for {@code PATCH /internal/queries/{picsureId}} on pic-sure-operations-service. Mirrors
 * {@code edu.harvard.hms.dbmi.avillach.operations.query.UpdateQueryRequest} byte-for-byte: every field is nullable and means "leave
 * unchanged" when absent.
 */
public record UpdateQueryRequest(String status, String resourceResultId, String metadata) {
}
