package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonValue;
import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;

/**
 * The {@code /open/validate} answer for a gateway that sends {@code "responseVersion": 2}. Key fields describe a key PSAMA verified and are
 * set only on a grant. They are null when no key was presented, when the presented key failed verification, and on every denial. The
 * presented key itself is never part of the response.
 */
public record OpenAccessValidationResponse(
    boolean valid, ApiKeyType keyType, String keyId, String displayPrefix, Denial denial, String refreshedToken
) {

    /**
     * Why a request was denied. {@link #KEY_INVALID} covers unknown, malformed, expired, and revoked keys alike, so the response does not
     * reveal which of those it was.
     */
    public enum Denial {
        KEY_MISSING("key_missing"), KEY_INVALID("key_invalid"), RULES("rules");

        private final String value;

        Denial(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /** A grant. {@code verifiedKey} is null when the request carried no key, or one that failed verification with enforcement off. */
    public static OpenAccessValidationResponse granted(ApiKey verifiedKey) {
        if (verifiedKey == null) {
            return new OpenAccessValidationResponse(true, null, null, null, null, null);
        }
        return new OpenAccessValidationResponse(
            true, verifiedKey.getKeyType(), verifiedKey.getUuid() == null ? null : verifiedKey.getUuid().toString(),
            verifiedKey.getDisplayPrefix(), null, null
        );
    }

    public static OpenAccessValidationResponse denied(Denial denial) {
        return new OpenAccessValidationResponse(false, null, null, null, denial, null);
    }

    // refreshedToken will carry a live session credential; the default record toString would embed it, one accidental log statement away
    // from a leak
    @Override
    public String toString() {
        return "OpenAccessValidationResponse[valid=%s, keyType=%s, keyId=%s, displayPrefix=%s, denial=%s, refreshedToken=%s]"
            .formatted(valid, keyType, keyId, displayPrefix, denial, refreshedToken == null ? null : "REDACTED");
    }
}
