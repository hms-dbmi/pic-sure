package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DB-free auth-chain configuration, bound from {@code picsure.gateway.security.*}. Backs {@link SecurityConfig}'s filter/bean wiring; see
 * each field's referenced env var for the operational knob it maps to.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewaySecurityProperties(
    List<String> allowListPrefixes,
    // OpenAccessFilter takes the open path here even with a bearer token present. Distinct from
    // allowListPrefixes, which skips authentication entirely.
    List<String> openPathPrefixes, boolean openAccessEnabled,
    // GATEWAY_AUTH_MAX_BODY_BYTES -- auth-buffering cap; 413 over it
    int maxBodyBytes, String introspectionUrl, String openAccessValidateUrl, String serviceToken,
    // OPERATIONS_SERVICE_URL -- for QueryAuthFetcher dispatch (dispatch lives on operations-service,
    // the sole DB owner)
    String operationsServiceUrl,
    // QUERY_SERVICE_INTERNAL_TOKEN -- X-PIC-SURE-INTERNAL-TOKEN, same value sent to operations-service now
    String queryServiceInternalToken,
    // GATEWAY_AUTH_CONNECT_TIMEOUT / GATEWAY_AUTH_READ_TIMEOUT. Bounds on the auth-boundary HTTP clients,
    // meaning PSAMA introspection, open-validate, and query-service dispatch. Never raise past the ~60s at the
    // proxy layers (httpd Timeout, ALB idle timeout), where the wait becomes a 504 anyway.
    Duration authConnectTimeout, Duration authReadTimeout
) {

    static final Duration DEFAULT_AUTH_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration DEFAULT_AUTH_READ_TIMEOUT = Duration.ofSeconds(10);

    public GatewaySecurityProperties {
        allowListPrefixes = allowListPrefixes == null ? List.of() : List.copyOf(allowListPrefixes);
        openPathPrefixes = openPathPrefixes == null ? List.of() : List.copyOf(openPathPrefixes);
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 10_485_760; // 10 MiB default (GATEWAY_AUTH_MAX_BODY_BYTES)
        }
        // Non-positive counts as unset. Zero or negative means "wait forever" to the underlying HTTP client.
        authConnectTimeout = positiveOrDefault(authConnectTimeout, DEFAULT_AUTH_CONNECT_TIMEOUT);
        authReadTimeout = positiveOrDefault(authReadTimeout, DEFAULT_AUTH_READ_TIMEOUT);
    }

    private static Duration positiveOrDefault(Duration configured, Duration fallback) {
        return configured == null || configured.isZero() || configured.isNegative() ? fallback : configured;
    }
}
