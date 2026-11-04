package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code X-Client-Type} values a client may present. A value is matched case-insensitively and resolves to the spelling listed here, so
 * audit events carry one form of each caller. {@code mcp} and {@code service} are reserved for callers a gateway filter has verified and
 * can never be listed.
 */
public final class ClientTypeAllowlist {

    /** The caller recorded and forwarded when a request names none. It is always accepted and cannot be unlisted. */
    public static final String UNKNOWN = "UNKNOWN";

    /** Caller types that only a verified source may claim. */
    public static final List<String> RESERVED = List.of("mcp", "service");

    /** The clients PIC-SURE ships: the two adapters and the web frontend. */
    public static final ClientTypeAllowlist DEFAULT = new ClientTypeAllowlist(List.of("PYTHON_ADAPTER", "R_ADAPTER", "FRONTEND"));

    private final Map<String, String> canonicalByLowerCase = new LinkedHashMap<>();

    /**
     * Builds an allowlist from configured values.
     *
     * @param allowed the accepted client types; blank entries are ignored
     * @throws IllegalArgumentException if an entry is a reserved caller type
     */
    public ClientTypeAllowlist(Collection<String> allowed) {
        for (String value : allowed) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (RESERVED.stream().anyMatch(trimmed::equalsIgnoreCase)) {
                throw new IllegalArgumentException("Client type '" + trimmed + "' is reserved for verified callers and cannot be allowed");
            }
            canonicalByLowerCase.put(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
    }

    /**
     * Looks up a client-supplied {@code X-Client-Type} value.
     *
     * @param claimed the raw header value, possibly null
     * @return the listed spelling of the client type ({@link #UNKNOWN} included), or empty when the value is null, blank, reserved, or not
     *         listed
     */
    public Optional<String> resolve(String claimed) {
        if (claimed == null) {
            return Optional.empty();
        }
        String key = claimed.trim().toLowerCase(Locale.ROOT);
        if (key.equals(UNKNOWN.toLowerCase(Locale.ROOT))) {
            return Optional.of(UNKNOWN);
        }
        return Optional.ofNullable(canonicalByLowerCase.get(key));
    }
}
