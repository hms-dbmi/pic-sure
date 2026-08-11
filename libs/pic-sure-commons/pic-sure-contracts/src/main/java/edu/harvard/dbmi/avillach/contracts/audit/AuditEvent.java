package edu.harvard.dbmi.avillach.contracts.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
public record AuditEvent(@JsonProperty("event_type") //
String eventType, @JsonProperty("action") String action, @JsonProperty("client_type") String clientType,
    @JsonProperty("caller") String caller, @JsonProperty("session_id") String sessionId, @JsonProperty("request") RequestInfo request,
    @JsonProperty("metadata") Map<String, Object> metadata, @JsonProperty("error") //
    Map<String, Object> error
) {
}
