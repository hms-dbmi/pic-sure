package edu.harvard.hms.dbmi.avillach.auth.utils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

public final class AuditAttributes {
    public static final String EVENT_TYPE = "audit.event_type";
    public static final String ACTION = "audit.action";
    private static final String METADATA_PREFIX = "audit.ctx.";

    private AuditAttributes() {}

    public static void putMetadata(HttpServletRequest request, String key, Object value) {
        if (request != null && key != null && value != null) {
            request.setAttribute(METADATA_PREFIX + key, value);
        }
    }

    public static Map<String, Object> getMetadata(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        if (request == null) {
            return result;
        }
        var names = request.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith(METADATA_PREFIX)) {
                result.put(name.substring(METADATA_PREFIX.length()), request.getAttribute(name));
            }
        }
        return result;
    }

    public static String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
