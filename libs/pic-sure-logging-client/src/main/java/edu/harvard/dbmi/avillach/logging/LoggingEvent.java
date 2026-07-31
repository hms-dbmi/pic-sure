package edu.harvard.dbmi.avillach.logging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The emitter-side handle on one audit record. This is a builder with validation, not a model: what it carries -- and what
 * {@link LoggingClient} puts on the socket -- is the shared {@code contracts.audit.AuditEvent} record that the logging service reads back,
 * so the two ends of the audit hop cannot drift apart.
 *
 * <p>Named {@code LoggingEvent} rather than {@code AuditEvent} because this package already declares an {@code @AuditEvent} annotation that
 * the services' audit interceptors put on controller methods.
 *
 * <p>Use {@link #builder(String)} to construct instances. The caps it enforces on {@code metadata} and {@code error} are the same ones the
 * logging service enforces at intake, so an over-sized event fails at the call site instead of being silently dropped over the wire.
 */
public final class LoggingEvent {

    private static final int MAX_METADATA_KEYS = 50;
    private static final int MAX_ERROR_KEYS = 20;

    private final edu.harvard.dbmi.avillach.contracts.audit.AuditEvent event;

    private LoggingEvent(edu.harvard.dbmi.avillach.contracts.audit.AuditEvent event) {
        this.event = event;
    }

    /**
     * The wire form: Jackson serializes this event as the shared contract record, with the record's own snake_case names and NON_NULL
     * inclusion.
     */
    @JsonValue
    public edu.harvard.dbmi.avillach.contracts.audit.AuditEvent toAuditEvent() {
        return event;
    }

    /**
     * Reads an event back off the wire. Deliberately skips the builder's validation: a record that already exists is being described, not
     * created, and rejecting it here would only lose it.
     */
    @JsonCreator
    static LoggingEvent fromAuditEvent(edu.harvard.dbmi.avillach.contracts.audit.AuditEvent event) {
        return new LoggingEvent(event);
    }

    public String getEventType() {
        return event.eventType();
    }

    public String getAction() {
        return event.action();
    }

    public String getClientType() {
        return event.clientType();
    }

    public String getSessionId() {
        return event.sessionId();
    }

    public RequestInfo getRequest() {
        return event.request();
    }

    public Map<String, Object> getMetadata() {
        return event.metadata();
    }

    public Map<String, Object> getError() {
        return event.error();
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
            // "caller" stays null: the logging service derives the identity from the Authorization header the client forwards.
            return new LoggingEvent(
                new edu.harvard.dbmi.avillach.contracts.audit.AuditEvent(
                    eventType, action, clientType, null, sessionId, request, defensiveCopy(metadata), defensiveCopy(error)
                )
            );
        }

        private static Map<String, Object> defensiveCopy(Map<String, Object> source) {
            return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    /**
     * Returns a copy of this event with the given clientType applied. All other fields are preserved. Used by {@link LoggingClient} to
     * apply config defaults.
     *
     * <p>Deliberately does NOT re-run the builder's validation, which the previous implementation did by round-tripping through
     * {@link #builder(String)}. Two reasons. First it cannot fire: the only way to obtain a LoggingEvent is {@code builder(…).build()} or
     * {@link #fromAuditEvent}, and this method changes nothing the builder checks -- not the event type, not the metadata or error maps.
     * Second, if it somehow did fire it would fire in the wrong place: {@link LoggingClient#send} calls this outside its try/catch, so a
     * throw here would propagate out of a call that is documented never to throw and would fail the user's request over an audit record.
     * Validation belongs at construction, where the caller can still do something about it.
     */
    LoggingEvent withClientType(String clientType) {
        return new LoggingEvent(
            new edu.harvard.dbmi.avillach.contracts.audit.AuditEvent(
                event.eventType(), event.action(), clientType, event.caller(), event.sessionId(), event.request(), event.metadata(),
                event.error()
            )
        );
    }

    @Override
    public String toString() {
        return "LoggingEvent{eventType='" + getEventType() + "', action='" + getAction() + "', clientType='" + getClientType()
            + "', sessionId='" + getSessionId() + "'}";
    }
}
