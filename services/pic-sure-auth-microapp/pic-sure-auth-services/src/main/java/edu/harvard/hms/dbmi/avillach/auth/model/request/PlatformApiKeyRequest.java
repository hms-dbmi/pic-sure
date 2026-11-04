package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A never-expiring key must be an explicit opt-in via {@code neverExpires}: after Jackson binding, an absent {@code expiresAt} is
 * indistinguishable from an explicit null, and "no expiry chosen" must default to the configured platform TTL rather than silently minting
 * a non-expiring key. Setting both fields is rejected.
 */
@Schema(
    description = "A request to mint a PLATFORM API key for a partner service. Give expiresAt for an explicit expiry, neverExpires for a "
        + "key that never expires, or neither for the configured platform default; both together are rejected. Any other member is ignored."
)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformApiKeyRequest(
    @Schema(
        description = "Name of the partner service the key is for, at most 255 characters.", example = "Partner service",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String name,
    @Schema(
        description = "Contact email for the key, at most 255 characters.", example = "researcher@example.org",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String email,
    @Schema(
        description = "When the key expires, as an ISO instant in the future. Leave out to use the platform default.",
        example = "2026-09-30T14:05:00Z"
    ) Instant expiresAt,
    @Schema(
        description = "True to mint a key that never expires. Cannot be combined with expiresAt. Absent means false."
    ) boolean neverExpires
) {
}
