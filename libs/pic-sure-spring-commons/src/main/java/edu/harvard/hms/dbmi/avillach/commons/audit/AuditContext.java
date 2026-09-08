package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-request holder for audit metadata such as username and resource id. Services populate it with domain-specific context, and
 * {@link AuditLoggingFilter} merges that context into the emitted event. It has a no-argument constructor so callers can scope one instance
 * per request with or without a dependency-injection container.
 */
public class AuditContext {

    private final Map<String, Object> metadata = new HashMap<>();

    public void put(String key, Object value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
    }

    /** Read-only live view of the accumulated metadata; use {@link #put(String, Object)} to add entries. */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
