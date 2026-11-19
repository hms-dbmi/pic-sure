package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.List;

/**
 * PSAMA API keys and open-access session tokens arrive as {@code Authorization: Bearer picsure_...}, the same header a login token uses.
 * Only the prefixes PSAMA's {@code ApiKeyService} issues for open access mark a key; any other value, including another {@code picsure_}
 * token, is left for introspection.
 */
public final class ApiKeyBearer {

    /** {@code picsure_u_} (user), {@code picsure_p_} (platform) and {@code picsure_s_} (open-access session), copied from PSAMA. */
    private static final List<String> KEY_PREFIXES = List.of("picsure_u_", "picsure_p_", "picsure_s_");

    private static final String SCHEME = "Bearer ";

    private ApiKeyBearer() {}

    /** Returns the key carried by an {@code Authorization} header value, or {@code null} if the value is not a bearer PSAMA key. */
    public static String extract(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        String token = authorization.substring(SCHEME.length()).trim();
        return KEY_PREFIXES.stream().anyMatch(token::startsWith) ? token : null;
    }
}
