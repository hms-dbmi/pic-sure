package edu.harvard.dbmi.avillach.logging.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEvent(
    @JsonProperty("event_type") String eventType,
    @JsonProperty("action") String action,
    @JsonProperty("client_type") String clientType,
    @JsonProperty("request") RequestInfo request,
    @JsonProperty("metadata") Map<String, Object> metadata,
    @JsonProperty("error") Map<String, Object> error
) {}
