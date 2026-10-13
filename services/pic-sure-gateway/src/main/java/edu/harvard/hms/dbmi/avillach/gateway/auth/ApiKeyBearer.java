package edu.harvard.hms.dbmi.avillach.gateway.auth;

/**
 * PSAMA API keys and open-access session tokens arrive as {@code Authorization: Bearer picsure_...}, the same header a login token uses.
 * The {@code picsure_} prefix tells them apart: a login token is a JWT and starts with {@code eyJ}.
 */
public final class ApiKeyBearer {

    /** Shared by every key type: {@code picsure_u_} (user), {@code picsure_p_} (platform), {@code picsure_s_} (open-access session). */
    public static final String KEY_PREFIX = "picsure_";

    private static final String SCHEME = "Bearer ";

    private ApiKeyBearer() {
    }

    /** Returns the key carried by an {@code Authorization} header value, or {@code null} if the value is not a bearer PSAMA key. */
    public static String extract(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        String token = authorization.substring(SCHEME.length()).trim();
        return token.startsWith(KEY_PREFIX) ? token : null;
    }
}
