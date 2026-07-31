package edu.harvard.dbmi.avillach.contracts.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * One audit record posted to the logging service's intake endpoint. The wire names are snake_case and the {@code @JsonProperty} names below
 * -- not the Java accessor names -- are the contract.
 *
 * <p>Unlike every other contract in this module this reader stays TOLERANT of unknown properties. Audit intake is fire-and-forget from a
 * request-handling filter: emitters across services routinely add a key before the collector learns about it, and an audit event must never
 * be the reason a user's request fails. Anything not modelled here is dropped, never rejected.
 *
 * <p>Writing is NON_NULL: an emitter populates the fields it knows about and leaves the rest unset, and those absences have always been
 * absences on the wire rather than explicit nulls.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A single audit record posted to the logging service; unknown properties are dropped, never rejected")
public record AuditEvent(
    @JsonProperty("event_type") @Schema(description = "Category of the event, e.g. \"api_request\"; required by the collector") //
    String eventType, @JsonProperty("action") @Schema(description = "The operation that took place, e.g. \"query.sync\"") String action,
    @JsonProperty("client_type") @Schema(description = "Which kind of caller produced the event, e.g. \"gateway\"") String clientType,
    @JsonProperty("caller") @Schema(description = "Identity the event is attributed to") String caller,
    @JsonProperty("session_id") @Schema(description = "Session the event belongs to") String sessionId,
    @JsonProperty("request") @Schema(description = "HTTP details of the request that produced the event") RequestInfo request,
    @JsonProperty(
        "metadata"
    ) @Schema(description = "Open-ended event detail; the collector caps this at 50 keys") Map<String, Object> metadata,
    @JsonProperty("error") @Schema(description = "Failure detail when the audited operation failed; the collector caps this at 20 keys") //
    Map<String, Object> error
) {
}
