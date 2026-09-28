package edu.harvard.hms.dbmi.avillach.auth.model.response;

import java.time.Instant;

/**
 * A new open-access session. {@code token} is the {@code picsure_s_} credential the browser sends as its API key; it is not stored and
 * appears nowhere else.
 */
public record OpenSessionResponse(String token, Instant expiresAt) {

    // the default record toString would embed a live credential, one accidental log statement away from a leak
    @Override
    public String toString() {
        return "OpenSessionResponse[token=REDACTED, expiresAt=%s]".formatted(expiresAt);
    }
}
