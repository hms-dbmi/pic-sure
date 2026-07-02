package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-request holder for audit metadata (username, resource id, etc.), modeled on the legacy
 * {@code edu.harvard.dbmi.avillach.service.AuditContext}. Services populate this with domain-specific context; {@link AuditLoggingFilter}
 * merges it into the emitted logging event's metadata. No-arg-constructable so callers can scope one instance per request without a DI
 * container (e.g. a request-scoped Spring bean, or plain {@code new} in a filter chain).
 */
public class AuditContext {

    private final Map<String, Object> metadata = new HashMap<>();

    public void put(String key, Object value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
