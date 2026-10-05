package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;

import java.time.Instant;
import java.util.UUID;

/**
 * The only place a plaintext API key ever appears. Returned once at creation; the key is not recoverable afterwards.
 */
public record ApiKeyCreationResponse(String apiKey, UUID uuid, String displayPrefix, ApiKeyType keyType, Instant expiresAt) {

    // the default record toString would embed the plaintext key, one accidental log statement away from a leak
    @Override
    public String toString() {
        return "ApiKeyCreationResponse[apiKey=REDACTED, uuid=%s, displayPrefix=%s, keyType=%s, expiresAt=%s]"
            .formatted(uuid, displayPrefix, keyType, expiresAt);
    }
}
