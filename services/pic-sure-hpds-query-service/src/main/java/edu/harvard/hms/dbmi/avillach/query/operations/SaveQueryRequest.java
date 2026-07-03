package edu.harvard.hms.dbmi.avillach.query.operations;

/**
 * Request body for {@code POST /internal/queries} on pic-sure-operations-service. Mirrors
 * {@code edu.harvard.hms.dbmi.avillach.operations.query.SaveQueryRequest} byte-for-byte -- this service is DB-free and reaches its
 * persistence store solely through {@link OperationsClient}, so this is a client-side copy of the operations-service DTO, not a shared
 * dependency. {@code status} is the {@code edu.harvard.dbmi.avillach.domain.PicSureStatus} enum NAME (e.g. {@code "QUEUED"}), kept as a
 * plain string on the wire. {@code metadata} is base64-encoded bytes, or {@code null} when there is none.
 */
public record SaveQueryRequest(String query, String resourceResultId, String status, String version, String metadata) {
}
