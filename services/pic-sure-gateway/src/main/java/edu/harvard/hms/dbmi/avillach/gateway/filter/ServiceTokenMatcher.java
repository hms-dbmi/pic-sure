package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Compares a presented shared secret against the current and the previous (being rotated out) configured secrets in constant time. Each
 * configured secret is trimmed, so stray whitespace from an environment file does not make the real secret fail to match; null or blank
 * means none is configured.
 */
final class ServiceTokenMatcher {

    private final byte[] current;
    private final byte[] previous;

    ServiceTokenMatcher(String currentToken, String previousToken) {
        this.current = configuredBytes(currentToken);
        this.previous = configuredBytes(previousToken);
    }

    /** @return true when neither secret is configured, so every presented value will be rejected */
    boolean isUnconfigured() {
        return current == null && previous == null;
    }

    boolean matches(String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean matchesCurrent = current != null && MessageDigest.isEqual(current, candidate);
        boolean matchesPrevious = previous != null && MessageDigest.isEqual(previous, candidate);
        return matchesCurrent | matchesPrevious;
    }

    private static byte[] configuredBytes(String token) {
        return token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }
}
