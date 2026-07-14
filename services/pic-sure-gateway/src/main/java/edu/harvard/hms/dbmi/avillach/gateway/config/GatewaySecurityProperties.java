package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DB-free auth-chain configuration, bound from {@code picsure.gateway.security.*}. Backs {@link SecurityConfig}'s filter/bean wiring; see
 * each field's referenced env var for the operational knob it maps to.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewaySecurityProperties(
    List<String> allowListPrefixes, boolean openAccessEnabled, String userIdClaim,
    // GATEWAY_AUTH_MAX_BODY_BYTES -- auth-buffering cap; 413 over it
    int maxBodyBytes, String introspectionUrl, String openAccessValidateUrl, String serviceToken,
    // HPDS_QUERY_SERVICE_URL -- no longer read by QueryAuthFetcher (dispatch now points at operationsServiceUrl
    // below), kept because the same env var also feeds the /hpds/** route and query-service health entry.
    String queryServiceUrl,
    // OPERATIONS_SERVICE_URL -- for QueryAuthFetcher dispatch (dispatch lives on operations-service,
    // the sole DB owner)
    String operationsServiceUrl,
    // QUERY_SERVICE_INTERNAL_TOKEN -- X-PIC-SURE-INTERNAL-TOKEN, same value sent to operations-service now
    String queryServiceInternalToken
) {
    public GatewaySecurityProperties {
        allowListPrefixes = allowListPrefixes == null ? List.of() : List.copyOf(allowListPrefixes);
        if (userIdClaim == null) {
            userIdClaim = "userId";
        }
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 10_485_760; // 10 MiB default (GATEWAY_AUTH_MAX_BODY_BYTES)
        }
    }
}
