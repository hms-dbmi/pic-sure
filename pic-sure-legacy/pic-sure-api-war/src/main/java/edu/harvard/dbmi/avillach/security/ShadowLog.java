package edu.harvard.dbmi.avillach.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Throwaway, flag-gated {@code side=WF} half of the gateway-parity shadow-logging pipeline (removed in Phase 7). Writes one minified JSON
 * line per call to the {@code picsure.shadow} SLF4J logger at INFO, matching the plan's Global-Constraints schema verbatim (field names AND
 * order) so a standalone reconciler can join these lines with the gateway's {@code side=GW} lines (built in gateway's
 * {@code edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowSupport} / {@code ShadowRecord}) by {@code correlationId}.
 *
 * <pre>{@code
 * {
 *   "side": "WF",
 *   "correlationId": "uuid",
 *   "channel": "introspection",
 *   "tokenHash": "sha256hex-or-null",
 *   "targetService": "/path",
 *   "query": {},
 *   "formattedQueryPresent": false,
 *   "ipAddress": null,
 *   "decision": "active"
 * }
 * }</pre>
 *
 * Gated by env {@code PICSURE_SHADOW_LOGGING} (default {@code false}/absent = completely inert: no log lines, no behavior change, no
 * performance cost beyond a single volatile read). {@code targetService} is the WAR's <b>raw</b> path
 * ({@code requestContext.getUriInfo().getPath()}) -- the reconciler canonicalizes it independently.
 */
public final class ShadowLog {

    private static final Logger SHADOW = LoggerFactory.getLogger("picsure.shadow");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final boolean ENV_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("PICSURE_SHADOW_LOGGING", "false"));

    /**
     * Test-only override for {@link #enabled()}. {@code null} means "use {@link #ENV_ENABLED}". Package-private so only tests in this
     * package can flip the flag without requiring a JVM restart with a different environment (the env-derived default is a
     * {@code static final} baked in at class-init).
     */
    private static volatile Boolean testOverride = null;

    private ShadowLog() {}

    public static boolean enabled() {
        Boolean override = testOverride;
        return override != null ? override : ENV_ENABLED;
    }

    static void setEnabledForTest(Boolean value) {
        testOverride = value;
    }

    /**
     * Lowercase hex SHA-256 of the exact bearer credential, UTF-8 bytes. Byte-identical to the gateway's
     * {@code ShadowSupport.tokenHash} so the two sides' hashes are directly comparable. Null or empty input returns {@code null}.
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

    /** Renders a {@code side=WF, channel=introspection} record. Pure function -- does not check {@link #enabled()}. */
    public static String renderIntrospection(String correlationId, String tokenHash, String rawPath, Object query, boolean active) {
        return render(correlationId, "introspection", tokenHash, rawPath, query, null, active ? "active" : "inactive");
    }

    /** Renders a {@code side=WF, channel=open-access} record. Pure function -- does not check {@link #enabled()}. */
    public static String renderOpenAccess(String correlationId, String rawPath, Object query, String ipAddress, boolean allowed) {
        return render(correlationId, "open-access", null, rawPath, query, ipAddress, allowed ? "allow" : "deny");
    }

    private static String render(
        String correlationId, String channel, String tokenHash, String rawPath, Object query, String ipAddress, String decision
    ) {
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("side", "WF");
            rec.put("correlationId", correlationId);
            rec.put("channel", channel);
            rec.put("tokenHash", tokenHash);
            rec.put("targetService", rawPath);
            rec.put("query", query);
            rec.put("formattedQueryPresent", false);
            rec.put("ipAddress", ipAddress);
            rec.put("decision", decision);
            return MAPPER.writeValueAsString(rec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes one minified {@code channel=introspection} JSON line to {@code picsure.shadow} at INFO, only when {@link #enabled()}. */
    public static void emitIntrospection(String correlationId, String tokenHash, String rawPath, Object query, boolean active) {
        if (!enabled()) return;
        SHADOW.info(renderIntrospection(correlationId, tokenHash, rawPath, query, active));
    }

    /** Writes one minified {@code channel=open-access} JSON line to {@code picsure.shadow} at INFO, only when {@link #enabled()}. */
    public static void emitOpenAccess(String correlationId, String rawPath, Object query, String ipAddress, boolean allowed) {
        if (!enabled()) return;
        SHADOW.info(renderOpenAccess(correlationId, rawPath, query, ipAddress, allowed));
    }
}
