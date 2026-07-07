package edu.harvard.dbmi.avillach.dictionary;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

public final class AuditAttributes {
    public static final String EVENT_TYPE = "audit.event_type";
    public static final String ACTION = "audit.action";
    private static final String METADATA = "audit.metadata";

    private AuditAttributes() {}

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
