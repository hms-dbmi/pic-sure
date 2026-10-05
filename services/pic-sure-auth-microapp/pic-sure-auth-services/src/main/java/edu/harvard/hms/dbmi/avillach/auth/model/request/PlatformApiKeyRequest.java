package edu.harvard.hms.dbmi.avillach.auth.model.request;

import java.time.Instant;

/**
 * A never-expiring key must be an explicit opt-in via {@code neverExpires}: after Jackson binding, an absent {@code expiresAt} is
 * indistinguishable from an explicit null, and "no expiry chosen" must default to the configured platform TTL rather than silently minting
 * a non-expiring key. Setting both fields is rejected.
 */
public record PlatformApiKeyRequest(String name, String email, Instant expiresAt, boolean neverExpires) {
}
