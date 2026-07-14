package edu.harvard.hms.dbmi.avillach.hpds.processing.audit;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility for setting audit metadata on the current request.
 * Controllers call {@link #putMetadata} to attach domain-specific context
 * (query_id, result_type, etc.) that the {@link AuditLoggingFilter} merges
 * into the logging event.
 */
public final class AuditAttributes {

    public static final String EVENT_TYPE = "audit.event_type";
    public static final String ACTION = "audit.action";
    private static final String METADATA = "audit.metadata";

    private AuditAttributes() {
    }

    public static void putMetadata(HttpServletRequest request, String key, Object value) {
        if (request != null && key != null && value != null) {
            getMetadata(request).put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMetadata(HttpServletRequest request) {
        Map<String, Object> metadata = (Map<String, Object>) request.getAttribute(METADATA);
        if (metadata == null) {
            metadata = new HashMap<>();
            request.setAttribute(METADATA, metadata);
        }
        return metadata;
    }
}
