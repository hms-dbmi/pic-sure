package edu.harvard.dbmi.avillach.service;

import java.util.HashMap;
import java.util.Map;

import javax.enterprise.context.RequestScoped;

/**
 * Request-scoped holder for audit metadata. Services populate this with domain-specific
 * context (resource_id, query_id, etc.) and the AuditLoggingFilter merges it into the
 * logging event alongside request-level metadata (IP, duration, status).
 */
@RequestScoped
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
