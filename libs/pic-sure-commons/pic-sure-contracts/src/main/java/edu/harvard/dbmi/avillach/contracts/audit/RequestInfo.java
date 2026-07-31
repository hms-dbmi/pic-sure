package edu.harvard.dbmi.avillach.contracts.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * HTTP details of the request an {@link AuditEvent} describes. The wire names are snake_case and the {@code @JsonProperty} names below --
 * not the Java accessor names -- are the contract.
 *
 * <p>Tolerant of unknown properties for the same reason as {@link AuditEvent}: audit intake must never fail a user's request. NON_NULL for
 * the same reason too -- an emitter fills in a handful of these thirteen fields and leaves the rest unset, and those absences have never
 * been on the wire.
 *
 * <p>Build one with {@link #builder()}: thirteen positional components, five of them adjacent Strings, is a transposition the compiler
 * cannot catch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Names every field at the call site. Unset fields stay null and, per the record's NON_NULL inclusion, stay off the wire.
     */
    public static final class Builder {

        private String requestId;
        private String method;
        private String url;
        private String queryString;
        private String srcIp;
        private String destIp;
        private Integer destPort;
        private String httpUserAgent;
        private String httpContentType;
        private Integer status;
        private Long bytes;
        private Long duration;
        private String referrer;

        private Builder() {}

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder queryString(String queryString) {
            this.queryString = queryString;
            return this;
        }

        public Builder srcIp(String srcIp) {
            this.srcIp = srcIp;
            return this;
        }

        public Builder destIp(String destIp) {
            this.destIp = destIp;
            return this;
        }

        public Builder destPort(Integer destPort) {
            this.destPort = destPort;
            return this;
        }

        public Builder httpUserAgent(String httpUserAgent) {
            this.httpUserAgent = httpUserAgent;
            return this;
        }

        public Builder httpContentType(String httpContentType) {
            this.httpContentType = httpContentType;
            return this;
        }

        public Builder status(Integer status) {
            this.status = status;
            return this;
        }

        public Builder bytes(Long bytes) {
            this.bytes = bytes;
            return this;
        }

        public Builder duration(Long duration) {
            this.duration = duration;
            return this;
        }

        public Builder referrer(String referrer) {
            this.referrer = referrer;
            return this;
        }

        public RequestInfo build() {
            return new RequestInfo(
                requestId, method, url, queryString, srcIp, destIp, destPort, httpUserAgent, httpContentType, status, bytes, duration,
                referrer
            );
        }
    }
}
