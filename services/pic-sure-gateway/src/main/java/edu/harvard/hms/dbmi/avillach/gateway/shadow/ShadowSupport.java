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
 * {@code correlationId}. This class is near-pure infra: the Phase-2 filters decide WHEN to emit (they call {@link #emit(ShadowRecord)} once
 * they build the introspection/open-access request they would have sent); {@link #emit(ShadowRecord)} adds only one narrow guard --
 * suppressing gateway-self-served paths ({@link #isGatewaySelfServed}) that could never be paired with a WildFly record.
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

    /**
     * Writes one minified JSON line for {@code record} to the {@code picsure.shadow} logger at INFO -- UNLESS the record's target service
     * is a path the gateway serves itself (see {@link #isGatewaySelfServed}), in which case nothing is emitted. This is the single shared
     * emission point for both auth filters' OBSERVE branches; the guard lives here so neither the introspection nor the open-access channel
     * can leak an unpairable record.
     */
    public static void emit(ShadowRecord record) {
        if (isGatewaySelfServed(record.targetService())) {
            // Gateway-self-served, non-owned, Spring-Security-permitted paths (e.g. /actuator/health/liveness probes, the OpenAPI doc)
            // take the OBSERVE branch because they are on the catch-all surface, but WildFly never sees them -- a SHADOW_GW record here
            // could never be paired and would surface as a spurious UNPAIRED (which fails the exit gate). Suppress it at the source.
            return;
        }
        try {
            SHADOW.info(MAPPER.writeValueAsString(record));
        } catch (JsonProcessingException e) {
            LoggerFactory.getLogger(ShadowSupport.class).error("Failed to serialize shadow record {}", record.correlationId(), e);
        }
    }

    /**
     * True iff {@code targetService} is a path the gateway serves itself (or otherwise never forwards to WildFly), so no WildFly
     * counterpart shadow record could ever pair with it. Covers {@code /actuator/**} (segment-safe: the actuator chain in
     * {@code SecurityConfig} / {@code ActuatorSecurityConfig}) and the gateway's self-served doc paths -- the OpenAPI JSON doc
     * ({@code .../openapi.json}) and Swagger UI ({@code /swagger-ui/**}) that {@code PsamaIntrospectionFilter}'s allow-list already treats
     * as non-introspected. Segment-safe so {@code /actuatorX} or {@code /swagger-ui-custom} (different routes) are NOT matched.
     */
    static boolean isGatewaySelfServed(String targetService) {
        if (targetService == null || targetService.isEmpty()) {
            return false;
        }
        return isSegmentPrefix(targetService, "/actuator") || isSegmentPrefix(targetService, "/swagger-ui")
            || isSegmentPrefix(targetService, "/openapi") || targetService.endsWith("/openapi.json");
    }

    /** Segment-safe prefix match: {@code path} equals {@code prefix} or continues with a {@code /} boundary (never a bare startsWith). */
    private static boolean isSegmentPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
