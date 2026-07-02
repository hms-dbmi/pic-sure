package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DB-free auth-chain configuration, bound from {@code picsure.gateway.security.*}. Backs {@link SecurityConfig}'s filter/bean wiring; see
 * each field's referenced env var for the operational knob it maps to.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewaySecurityProperties(
    // GATEWAY_AUTH_ENABLED -- master switch. false (default) => no auth/audit filters register, gateway is a pure
    // pass-through. Flip true ONLY during the coordinated cutover (after WildFly stops owning auth for these routes).
    boolean authEnabled, List<String> allowListPrefixes, boolean openAccessEnabled, String userIdClaim,
    // GATEWAY_AUTH_MAX_BODY_BYTES -- auth-buffering cap; 413 over it
    int maxBodyBytes, String introspectionUrl, String openAccessValidateUrl, String serviceToken,
    // HPDS_QUERY_SERVICE_URL -- for QueryAuthFetcher dispatch
    String queryServiceUrl,
    // QUERY_SERVICE_INTERNAL_TOKEN -- X-PIC-SURE-INTERNAL-TOKEN (S-M4)
    String queryServiceInternalToken,
    // GATEWAY_OWNS_QUERY_READ_AUTH -- false in Phase 2, true at Phase 4
    boolean gatewayOwnsQueryReadAuth,
    // GatewayAuthScope regex; default result/signed-url
    List<String> queryReadPaths
) {
    public GatewaySecurityProperties {
        allowListPrefixes = allowListPrefixes == null ? List.of() : List.copyOf(allowListPrefixes);
        queryReadPaths = queryReadPaths == null ? List.of() : List.copyOf(queryReadPaths);
        if (userIdClaim == null) {
            userIdClaim = "userId";
        }
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 10_485_760; // 10 MiB default (GATEWAY_AUTH_MAX_BODY_BYTES)
        }
    }
}
