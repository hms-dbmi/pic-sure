package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A new open-access session. {@code token} is the {@code picsure_s_} credential the browser sends as its API key; it is not stored and
 * appears nowhere else.
 */
@Schema(description = "A new open-access session.")
public record OpenSessionResponse(
    @Schema(
        description = "The session token, which the browser sends as its API key. It is not stored and appears nowhere else.",
        example = "picsure_s_eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI4Njk0ZTNkNCJ9.sig", requiredMode = Schema.RequiredMode.REQUIRED
    ) String token,
    @Schema(
        description = "When the token expires.", example = "2026-09-30T14:05:00Z", requiredMode = Schema.RequiredMode.REQUIRED
    ) Instant expiresAt
) {

    // the default record toString would embed a live credential, one accidental log statement away from a leak
    @Override
    public String toString() {
        return "OpenSessionResponse[token=REDACTED, expiresAt=%s]".formatted(expiresAt);
    }
}
