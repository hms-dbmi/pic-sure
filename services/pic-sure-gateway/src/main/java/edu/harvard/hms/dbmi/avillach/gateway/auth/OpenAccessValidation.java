package edu.harvard.hms.dbmi.avillach.gateway.auth;

/**
 * PSAMA's answer to an open-access validate. Key fields describe a key PSAMA verified and are set only on a grant; a valid result with null
 * key fields is anonymous, either because no key was presented or because PSAMA predates key identity. {@code denial} says why a request
 * was refused ({@link #DENIAL_KEY_MISSING}, {@link #DENIAL_KEY_INVALID}, {@link #DENIAL_RULES}) and is null on a grant or from an older
 * PSAMA. {@code refreshedToken} is a replacement open-access session token, set only on a grant to a session that is due for a refresh.
 */
public record OpenAccessValidation(
    boolean valid, String keyType, String keyId, String displayPrefix, String denial, String refreshedToken
) {

    public static final String DENIAL_KEY_MISSING = "key_missing";
    public static final String DENIAL_KEY_INVALID = "key_invalid";
    public static final String DENIAL_RULES = "rules";

    /** An older PSAMA answers with a bare boolean, which carries no key identity and no denial reason. */
    public static OpenAccessValidation fromBoolean(boolean valid) {
        return new OpenAccessValidation(valid, null, null, null, null, null);
    }

    // refreshedToken is a live session credential; the default record toString would embed it, one accidental log statement away from a
    // leak
    @Override
    public String toString() {
        return "OpenAccessValidation[valid=%s, keyType=%s, keyId=%s, displayPrefix=%s, denial=%s, refreshedToken=%s]"
            .formatted(valid, keyType, keyId, displayPrefix, denial, refreshedToken == null ? null : "REDACTED");
    }
}
