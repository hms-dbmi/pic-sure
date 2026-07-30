package edu.harvard.dbmi.avillach.contracts.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * HTTP details of the request an {@link AuditEvent} describes. The wire names are snake_case and the {@code @JsonProperty} names below --
 * not the Java accessor names -- are the contract.
 *
 * <p>Tolerant of unknown properties for the same reason as {@link AuditEvent}: audit intake must never fail a user's request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "HTTP details of the request an audit event describes; unknown properties are dropped, never rejected")
public record RequestInfo(
    @JsonProperty("request_id") @Schema(description = "Correlation id shared with the request's logs and downstream hops") String requestId,
    @JsonProperty("method") @Schema(description = "HTTP method, e.g. \"POST\"") String method,
    @JsonProperty("url") @Schema(description = "Request path, without the query string") String url,
    @JsonProperty("query_string") @Schema(description = "Raw query string, without the leading \"?\"") String queryString,
    @JsonProperty("src_ip") @Schema(description = "Client IP the request came from") String srcIp,
    @JsonProperty("dest_ip") @Schema(description = "IP of the service that handled the request") String destIp,
    @JsonProperty("dest_port") @Schema(description = "Port of the service that handled the request") Integer destPort,
    @JsonProperty("http_user_agent") @Schema(description = "User-Agent header sent by the client") String httpUserAgent,
    @JsonProperty("http_content_type") @Schema(description = "Content-Type header sent by the client") String httpContentType,
    @JsonProperty("status") @Schema(description = "HTTP status code the request was answered with") Integer status,
    @JsonProperty("bytes") @Schema(description = "Size of the response body in bytes") Long bytes,
    @JsonProperty("duration") @Schema(description = "Milliseconds spent handling the request") Long duration,
    @JsonProperty("referrer") @Schema(description = "Referer header sent by the client") String referrer
) {
}
