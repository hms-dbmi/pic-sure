package edu.harvard.dbmi.avillach.contracts.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HTTP details of the request an {@link AuditEvent} describes. The wire names are snake_case and the {@code @JsonProperty} names below --
 * not the Java accessor names -- are the contract.
 *
 * <p>Tolerant of unknown properties for the same reason as {@link AuditEvent}: audit intake must never fail a user's request. NON_NULL for
 * the same reason too -- an emitter fills in a handful of these thirteen fields and leaves the rest unset, and those absences have never
 * been on the wire.
 *
 * <p>Thirteen positional components, five of them adjacent Strings, is a transposition the compiler cannot catch -- but a builder is
 * emitter-side ergonomics, not part of the wire shape, so it lives with the emitters in {@code pic-sure-logging-client}
 * ({@code RequestInfoBuilder}). The logging service only ever deserializes this record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestInfo(
    @JsonProperty("request_id") String requestId, @JsonProperty("method") String method, @JsonProperty("url") String url,
    @JsonProperty("query_string") String queryString, @JsonProperty("src_ip") String srcIp, @JsonProperty("dest_ip") String destIp,
    @JsonProperty("dest_port") Integer destPort, @JsonProperty("http_user_agent") String httpUserAgent,
    @JsonProperty("http_content_type") String httpContentType, @JsonProperty("status") Integer status, @JsonProperty("bytes") Long bytes,
    @JsonProperty("duration") Long duration, @JsonProperty("referrer") String referrer
) {
}
