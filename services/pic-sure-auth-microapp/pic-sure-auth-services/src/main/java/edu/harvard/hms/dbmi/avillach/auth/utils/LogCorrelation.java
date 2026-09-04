package edu.harvard.hms.dbmi.avillach.auth.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces one-way correlation references for values that must not appear in a log line: OAuth authorization codes, identity-provider
 * subjects, and other stable identity material. The reference is a truncated SHA-256 of the value, so the same input always yields the same
 * reference and operators can still follow one login across log lines, but the original value cannot be read back out.
 */
public final class LogCorrelation {

    private static final int REFERENCE_BYTES = 8;
    private static final String ABSENT = "none";

    private LogCorrelation() {}

    /**
     * Returns a stable, non-reversible reference for a sensitive value.
     *
     * @param value the value to reference; may be null or blank
     * @return a 16-character hex reference, or {@code "none"} when there is no value
     */
    public static String reference(String value) {
        if (value == null || value.isBlank()) {
            return ABSENT;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[REFERENCE_BYTES];
            System.arraycopy(digest, 0, truncated, 0, REFERENCE_BYTES);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to build a log correlation reference", e);
        }
    }
}
