package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.UUID;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * The {@code query} member of {@link NamedDatasetDto}, containing only the persisted query fields consumers read. Keeping this as a DTO
 * prevents the gzip-compressed blob, the {@code metadata} byte array, and persistence associations from reaching the wire. The
 * {@code query} field contains decompressed query JSON, exactly what {@code Query#getQuery()} returns.
 *
 * <p>{@code startTime} is epoch milliseconds because the frontend passes it to {@code new Date(...)} and sorts it numerically. A
 * {@code Long} pins that contract regardless of ObjectMapper configuration.
 */
public record NamedDatasetQueryDto(UUID uuid, String query, Long startTime, PicSureStatus status) {
}
