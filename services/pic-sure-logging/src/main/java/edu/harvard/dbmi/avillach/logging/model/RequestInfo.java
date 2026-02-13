package edu.harvard.dbmi.avillach.logging.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestInfo(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("method") String method,
    @JsonProperty("url") String url,
    @JsonProperty("query_string") String queryString,
    @JsonProperty("src_ip") String srcIp,
    @JsonProperty("dest_ip") String destIp,
    @JsonProperty("dest_port") Integer destPort,
    @JsonProperty("http_user_agent") String httpUserAgent,
    @JsonProperty("http_content_type") String httpContentType,
    @JsonProperty("status") Integer status,
    @JsonProperty("bytes") Long bytes,
    @JsonProperty("duration") Long duration,
    @JsonProperty("referrer") String referrer
) {}
