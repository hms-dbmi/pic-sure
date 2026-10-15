package edu.harvard.hms.dbmi.avillach.auth.model.response;

import com.fasterxml.jackson.annotation.JsonValue;
import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code /open/validate} answer for a gateway that sends {@code "responseVersion": 2}. Key fields describe a key PSAMA verified and are
 * set only on a grant. They are null when no key was presented, when the presented key failed verification, and on every denial. The
 * presented key itself is never part of the response.
 */
@Schema(
    description = "The answer to an open-access validate that asked for responseVersion 2. The key fields describe the key PSAMA verified "
        + "and are set only on a grant to one. The presented key itself is never returned."
)
public record OpenAccessValidationResponse(
    @Schema(description = "Whether the request is granted.", requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,
    @Schema(
        description = "The kind of credential verified. Null on a denial, and on a grant to a request that presented no valid key.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) KeyType keyType,
    @Schema(
        description = "The id of the verified key, or the session id of a verified session. Null on a denial, and on a grant to a "
            + "request that presented no valid key.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) String keyId,
    @Schema(
        description = "The first characters of the verified key's body, as shown in the admin key list. Null on a denial, on a session "
            + "grant, and on a grant to a request that presented no valid key.",
        example = "AbCd1234", requiredMode = Schema.RequiredMode.REQUIRED
    ) String displayPrefix,
    @Schema(description = "Why the request was denied. Null on a grant.", requiredMode = Schema.RequiredMode.REQUIRED) Denial denial,
    @Schema(
        description = "A replacement open-access session token, when the presented session is due for a refresh. Null otherwise.",
        example = "picsure_s_eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI4Njk0ZTNkNCJ9.sig", requiredMode = Schema.RequiredMode.REQUIRED
    ) String refreshedToken
) {

    /**
     * The kind of credential a grant verified. {@link #USER} and {@link #PLATFORM} are stored API keys; {@link #SESSION} is a stateless
     * open-access session token, which is never generated, stored, or listed as an API key.
     */
    @Schema(description = "The kind of credential that was verified.")
    public enum KeyType {

        @Schema(description = "A key an anonymous user generated for themselves.")
        USER,

        @Schema(description = "A key an admin minted for a deployment or partner.")
        PLATFORM,

        @Schema(description = "An open-access session token from POST /open/session.")
        SESSION;

        /**
         * Maps a stored API key type to the corresponding validation response type.
         *
         * @throws NullPointerException if {@code stored} is null
         */
        static KeyType of(ApiKeyType stored) {
            return switch (stored) {
                case USER -> USER;
                case PLATFORM -> PLATFORM;
            };
        }
    }

    /**
     * Why a request was denied. {@link #KEY_INVALID} covers unknown, malformed, expired, and revoked keys alike, so the response does not
     * reveal which of those it was.
     */
    @Schema(description = "Why an open-access request was denied.")
    public enum Denial {

        @Schema(description = "A key is required and none was presented.")
        KEY_MISSING("key_missing"),

        @Schema(description = "The presented key is unknown, malformed, expired, or revoked.")
        KEY_INVALID("key_invalid"),

        @Schema(description = "The open-access rules did not permit the request.")
        RULES("rules");

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
            true, KeyType.of(verifiedKey.getKeyType()), verifiedKey.getUuid() == null ? null : verifiedKey.getUuid().toString(),
            verifiedKey.getDisplayPrefix(), null, null
        );
    }

    /**
     * A grant to an open-access session. {@code keyId} is the open-access session id; there is no display prefix. {@code refreshedToken} is
     * the replacement token when this one is due for a refresh, otherwise null.
     */
    public static OpenAccessValidationResponse grantedSession(String sessionId, String refreshedToken) {
        return new OpenAccessValidationResponse(true, KeyType.SESSION, sessionId, null, null, refreshedToken);
    }

    public static OpenAccessValidationResponse denied(Denial denial) {
        return new OpenAccessValidationResponse(false, null, null, null, denial, null);
    }

    // refreshedToken is a live session credential; the default record toString would embed it, one accidental log statement away from a
    // leak
    @Override
    public String toString() {
        return "OpenAccessValidationResponse[valid=%s, keyType=%s, keyId=%s, displayPrefix=%s, denial=%s, refreshedToken=%s]"
            .formatted(valid, keyType, keyId, displayPrefix, denial, refreshedToken == null ? null : "REDACTED");
    }
}
