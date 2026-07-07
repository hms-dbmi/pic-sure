package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DB-free auth-chain configuration, bound from {@code picsure.gateway.security.*}. Backs {@link SecurityConfig}'s filter/bean wiring; see
 * each field's referenced env var for the operational knob it maps to.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewaySecurityProperties(
    // GATEWAY_OWNS_AUTH -- master switch (same env var name the WAR side reads). false (default) => no auth/audit filters
    // register, gateway is a pure pass-through. Flip true ONLY during the coordinated cutover, and only once WildFly is
    // also configured with GATEWAY_OWNS_AUTH -- the two services must agree, or requests get double-authenticated or
    // the WAR ends up trusting gateway headers the gateway never validated.
    boolean authEnabled, List<String> allowListPrefixes, boolean openAccessEnabled, String userIdClaim,
    // GATEWAY_AUTH_MAX_BODY_BYTES -- auth-buffering cap; 413 over it
    int maxBodyBytes, String introspectionUrl, String openAccessValidateUrl, String serviceToken,
    // HPDS_QUERY_SERVICE_URL -- no longer read by QueryAuthFetcher (dispatch now points at operationsServiceUrl
    // below), kept because the same env var also feeds the /hpds/** route and query-service health entry.
    String queryServiceUrl,
    // OPERATIONS_SERVICE_URL -- for QueryAuthFetcher dispatch (dispatch lives on operations-service,
    // the sole DB owner)
    String operationsServiceUrl,
    // QUERY_SERVICE_INTERNAL_TOKEN -- X-PIC-SURE-INTERNAL-TOKEN, same value sent to operations-service now
    String queryServiceInternalToken,
    // GATEWAY_OWNS_QUERY_READ_AUTH -- false while WildFly still owns query-read auth, true once the gateway owns it
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
