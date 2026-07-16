package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors the server-side AuditEvent model. Use {@link #builder(String)} to construct instances.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LoggingEvent {

    private static final int MAX_METADATA_KEYS = 50;
    private static final int MAX_ERROR_KEYS = 20;

    @JsonProperty("event_type")
    private final String eventType;

    @JsonProperty("action")
    private final String action;

    @JsonProperty("client_type")
    private final String clientType;

    @JsonProperty("session_id")
    private final String sessionId;

    @JsonProperty("request")
    private final RequestInfo request;

    @JsonProperty("metadata")
    private final Map<String, Object> metadata;

    @JsonProperty("error")
    private final Map<String, Object> error;

    private LoggingEvent(Builder builder) {
        this.eventType = builder.eventType;
        this.action = builder.action;
        this.clientType = builder.clientType;
        this.sessionId = builder.sessionId;
        this.request = builder.request;
        this.metadata = builder.metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata)) : null;
        this.error = builder.error != null ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.error)) : null;
    }

    // Jackson deserialization constructor
    @SuppressWarnings("unused")
    private LoggingEvent() {
        this.eventType = null;
        this.action = null;
        this.clientType = null;
        this.sessionId = null;
        this.request = null;
        this.metadata = null;
        this.error = null;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAction() {
        return action;
    }

    public String getClientType() {
        return clientType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public RequestInfo getRequest() {
        return request;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Map<String, Object> getError() {
        return error;
    }

    /**
     * Create a builder with the required event type.
     *
     * @param eventType the event type (e.g. "QUERY", "LOGIN", "ACCESS")
     * @return a new builder
     */
    public static Builder builder(String eventType) {
        if (eventType == null || eventType.trim().isEmpty()) {
            throw new IllegalArgumentException("eventType is required");
        }
        return new Builder(eventType);
    }

    public static final class Builder {
        private final String eventType;
        private String action;
        private String clientType;
        private String sessionId;
        private RequestInfo request;
        private Map<String, Object> metadata;
        private Map<String, Object> error;

        private Builder(String eventType) {
            this.eventType = eventType;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder clientType(String clientType) {
            this.clientType = clientType;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder request(RequestInfo request) {
            this.request = request;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder error(Map<String, Object> error) {
            this.error = error;
            return this;
        }

        public LoggingEvent build() {
            if (metadata != null && metadata.size() > MAX_METADATA_KEYS) {
                throw new IllegalArgumentException("metadata must not exceed " + MAX_METADATA_KEYS + " keys, got " + metadata.size());
            }
            if (error != null && error.size() > MAX_ERROR_KEYS) {
                throw new IllegalArgumentException("error must not exceed " + MAX_ERROR_KEYS + " keys, got " + error.size());
            }
            return new LoggingEvent(this);
        }
    }

    /**
     * Returns a copy of this event with the given clientType applied. All other fields are preserved. Used by {@link LoggingClient} to
     * apply config defaults.
     */
    LoggingEvent withClientType(String clientType) {
        return LoggingEvent.builder(this.eventType).action(this.action).clientType(clientType).sessionId(this.sessionId)
            .request(this.request).metadata(this.metadata).error(this.error).build();
    }

    @Override
    public String toString() {
        return "LoggingEvent{eventType='" + eventType + "', action='" + action + "', clientType='" + clientType + "', sessionId='"
            + sessionId + "'}";
    }
}
