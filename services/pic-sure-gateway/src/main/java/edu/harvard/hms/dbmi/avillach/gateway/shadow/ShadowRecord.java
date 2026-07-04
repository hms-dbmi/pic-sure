package edu.harvard.hms.dbmi.avillach.gateway.shadow;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable, Jackson-serializable carrier for one line of the parity-verification shadow log. Field name AND order must match the plan's
 * Global Constraints schema verbatim -- the standalone reconciler (a separate module) parses this exact shape from both the gateway's
 * ({@code side=GW}) and WildFly's ({@code side=WF}) log lines and joins them by {@code correlationId}; a mismatch here silently breaks that
 * comparison.
 *
 * <pre>{@code
 * {
 *   "side": "GW", "correlationId": "uuid", "channel": "introspection", "tokenHash": "sha256hex-or-null", "targetService": "/path", "query":
 * {}, "formattedQueryPresent": false, "ipAddress": null, "decision": null } }</pre>
 *
 * <ul> <li>{@code side}: {@code "GW"} or {@code "WF"}. {@code channel}: {@code "introspection"} or {@code "open-access"}.
 * <li>{@code query}: the parsed query object with {@code resourceCredentials} already stripped, or {@code null}.
 * <li>{@code formattedQueryPresent}: WF-only informational flag; always {@code false} for GW. <li>{@code ipAddress}: open-access only
 * ({@code "OPEN_ACCESS:<host>"}), else {@code null}. <li>{@code decision}: WF-only -- {@code "active"}/{@code "inactive"} (introspection)
 * or {@code "allow"}/ {@code "deny"} (open-access); always {@code null} for GW. </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ShadowRecord(
    String side, String correlationId, String channel, String tokenHash, String targetService, Object query, boolean formattedQueryPresent,
    String ipAddress, String decision
) {

    /** Builds a GW-side {@code channel=introspection} record. {@code decision}/{@code ipAddress} are always null for GW. */
    public static ShadowRecord gwIntrospection(String correlationId, String tokenHash, String targetService, Object query) {
        return new ShadowRecord("GW", correlationId, "introspection", tokenHash, targetService, query, false, null, null);
    }

    /** Builds a GW-side {@code channel=open-access} record, carrying the {@code "OPEN_ACCESS:<host>"} ip marker. */
    public static ShadowRecord gwOpenAccess(String correlationId, String tokenHash, String targetService, Object query, String ipAddress) {
        return new ShadowRecord("GW", correlationId, "open-access", tokenHash, targetService, query, false, ipAddress, null);
    }
}
