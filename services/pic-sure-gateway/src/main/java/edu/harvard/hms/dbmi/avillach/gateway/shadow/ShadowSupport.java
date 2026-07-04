package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared shadow-logging support for the gateway side ({@code side=GW}) of the parity-verification pipeline. Writes one minified JSON line
 * per {@link ShadowRecord} to the {@code picsure.shadow} SLF4J logger at INFO. WildFly emits its own, independently flag-gated
 * {@code side=WF} lines (quarantined {@code pic-sure-legacy} JWTFilter); a standalone reconciler module joins the two streams by
 * {@code correlationId}. This class is pure infra: it does not decide WHEN to emit -- the Phase-2 filters (a later task) call
 * {@link #emit(ShadowRecord)} once they build the introspection/open-access request they would have sent.
 */
public final class ShadowSupport {

    /**
     * Request attribute key under which {@code CorrelationIdFilter} stores the per-request correlation id it mints. Downstream filters read
     * this attribute (rather than re-minting) so every shadow record for one request shares the same id, and so the reconciler can join the
     * gateway's records with WildFly's via the propagated {@code X-PICSURE-Shadow-Id} header.
     */
    public static final String ATTR_CORRELATION_ID = "picsure.shadow.correlationId";

    private static final Logger SHADOW = LoggerFactory.getLogger("picsure.shadow");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShadowSupport() {}

    /**
     * Lowercase hex SHA-256 of the exact bearer credential (the substring after {@code "Bearer "}), UTF-8 bytes. Identical algorithm on the
     * gateway and WildFly so the two sides' hashes are directly comparable. Purpose is log-safe equality only, never reversal. Null or
     * empty input returns {@code null}.
     */
    public static String tokenHash(String bearerToken) {
        if (bearerToken == null || bearerToken.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bearerToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA); this is unreachable in practice.
            throw new IllegalStateException(e);
        }
    }

    /** Writes one minified JSON line for {@code record} to the {@code picsure.shadow} logger at INFO. */
    public static void emit(ShadowRecord record) {
        try {
            SHADOW.info(MAPPER.writeValueAsString(record));
        } catch (JsonProcessingException e) {
            LoggerFactory.getLogger(ShadowSupport.class).error("Failed to serialize shadow record {}", record.correlationId(), e);
        }
    }
}
