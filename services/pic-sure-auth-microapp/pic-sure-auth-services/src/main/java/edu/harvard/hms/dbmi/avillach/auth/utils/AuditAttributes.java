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

    /**
     * The resource an authorization decision was about, derived from the target-service path -- {@code /hpds/auth/v3/query} is a decision
     * about {@code hpds}, {@code /picsure/proxy/dictionary/search} one about {@code picsure}. <p> This replaces digging
     * {@code resourceUUID} out of the request body: v3 query bodies do not carry one, so that lookup produced a null {@code resource_id} on
     * every audit record it still applied to. The path is the only resource identity the request actually has.
     *
     * @return the first path segment, or null when there is nothing to derive a label from
     */
    public static String resourceLabelForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!segment.isBlank()) {
                return segment;
            }
        }
        return null;
    }

    public static String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

}
