package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the server-side RequestInfo model. Use {@link #builder()} to construct instances.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RequestInfo {

    @JsonProperty("request_id")
    private final String requestId;

    @JsonProperty("method")
    private final String method;

    @JsonProperty("url")
    private final String url;

    @JsonProperty("query_string")
    private final String queryString;

    @JsonProperty("src_ip")
    private final String srcIp;

    @JsonProperty("dest_ip")
    private final String destIp;

    @JsonProperty("dest_port")
    private final Integer destPort;

    @JsonProperty("http_user_agent")
    private final String httpUserAgent;

    @JsonProperty("http_content_type")
    private final String httpContentType;

    @JsonProperty("status")
    private final Integer status;

    @JsonProperty("bytes")
    private final Long bytes;

    @JsonProperty("duration")
    private final Long duration;

    @JsonProperty("referrer")
    private final String referrer;

    private RequestInfo(Builder builder) {
        this.requestId = builder.requestId;
        this.method = builder.method;
        this.url = builder.url;
        this.queryString = builder.queryString;
        this.srcIp = builder.srcIp;
        this.destIp = builder.destIp;
        this.destPort = builder.destPort;
        this.httpUserAgent = builder.httpUserAgent;
        this.httpContentType = builder.httpContentType;
        this.status = builder.status;
        this.bytes = builder.bytes;
        this.duration = builder.duration;
        this.referrer = builder.referrer;
    }

    // Jackson deserialization constructor
    @SuppressWarnings("unused")
    private RequestInfo() {
        this(new Builder());
    }

    public String getRequestId() {
        return requestId;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getQueryString() {
        return queryString;
    }

    public String getSrcIp() {
        return srcIp;
    }

    public String getDestIp() {
        return destIp;
    }

    public Integer getDestPort() {
        return destPort;
    }

    public String getHttpUserAgent() {
        return httpUserAgent;
    }

    public String getHttpContentType() {
        return httpContentType;
    }

    public Integer getStatus() {
        return status;
    }

    public Long getBytes() {
        return bytes;
    }

    public Long getDuration() {
        return duration;
    }

    public String getReferrer() {
        return referrer;
    }

    public static Builder builder() {
        return new Builder();
    }

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
            return new RequestInfo(this);
        }
    }

    @Override
    public String toString() {
        return "RequestInfo{method='" + method + "', url='" + url + "', status=" + status + "}";
    }
}
