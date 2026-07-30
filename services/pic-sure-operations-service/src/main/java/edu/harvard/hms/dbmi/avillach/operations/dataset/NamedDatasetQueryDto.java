package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.UUID;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;

/**
 * The {@code query} member of {@link NamedDatasetDto}, mirroring the subset of the legacy {@code Query} entity's wire shape that consumers
 * actually read. The legacy WAR serialized the JPA entity itself; this stays a DTO so the gzip-compressed blob, the {@code metadata}
 * byte[], and the dropped {@code resource} association never reach the wire -- {@code query} is the DECOMPRESSED query JSON, exactly what
 * {@code Query#getQuery()} returns.
 *
 * <p>{@code startTime} is epoch MILLIS rather than the entity's {@code java.sql.Date}: legacy's Jackson wrote dates as numeric timestamps,
 * the frontend feeds this straight into {@code new Date(...)} and sorts on it numerically, and Spring Boot's ObjectMapper would otherwise
 * emit an ISO string (it disables {@code WRITE_DATES_AS_TIMESTAMPS} by default). Making it a {@code Long} pins the contract regardless of
 * ObjectMapper configuration.
 */
public record NamedDatasetQueryDto(UUID uuid, String query, Long startTime, PicSureStatus status) {
}
