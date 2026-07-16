package edu.harvard.dbmi.avillach.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Resolves a session identifier from an explicit header value, falling back to a deterministic hash of source IP and User-Agent. This
 * centralizes session ID logic that was previously duplicated across multiple PIC-SURE services. <p> The fallback hash uses SHA-256
 * truncated to 16 hex characters (64 bits), providing strong collision resistance even when many users share the same IP (e.g. behind a VPN
 * or institutional NAT). <p> No servlet dependency — accepts raw string values so it works in both javax.servlet (WildFly) and
 * jakarta.servlet (Spring Boot) environments.
 */
public final class SessionIdResolver {

    private static final int HASH_HEX_LENGTH = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SessionIdResolver() {}

    /**
     * Resolve a session identifier.
     *
     * @param sessionIdHeader the value of the {@code X-Session-Id} header (may be null or empty)
     * @param srcIp the client IP address (may be null)
     * @param userAgent the {@code User-Agent} header value (may be null)
     * @return a non-null session identifier — either the header value or a 16-char hex SHA-256 hash of IP + User-Agent
     */
    public static String resolve(String sessionIdHeader, String srcIp, String userAgent) {
        if (sessionIdHeader != null && !sessionIdHeader.isEmpty()) {
            return sessionIdHeader;
        }
        String raw = (srcIp != null ? srcIp : "") + "|" + (userAgent != null ? userAgent : "");
        return sha256Hex(raw, HASH_HEX_LENGTH);
    }

    static String sha256Hex(String input, int hexLength) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            // Convert first (hexLength / 2) bytes to hex
            int bytesNeeded = (hexLength + 1) / 2;
            StringBuilder sb = new StringBuilder(hexLength);
            for (int i = 0; i < bytesNeeded && i < hash.length; i++) {
                sb.append(HEX[(hash[i] >> 4) & 0x0f]);
                sb.append(HEX[hash[i] & 0x0f]);
            }
            return sb.substring(0, hexLength);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java spec; this should never happen
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
