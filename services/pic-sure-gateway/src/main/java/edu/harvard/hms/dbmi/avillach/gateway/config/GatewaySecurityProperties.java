package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicRoute;

/**
 * DB-free auth-chain configuration, bound from {@code picsure.gateway.security.*}. Backs {@link SecurityConfig}'s filter/bean wiring; see
 * each field's referenced env var for the operational knob it maps to. {@code publicRoutes} is the complete list of routes served without a
 * token; a malformed entry throws from {@link PublicRoute} during binding and stops the context.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewaySecurityProperties(
    List<PublicRoute> publicRoutes, boolean openAccessEnabled,
    // GATEWAY_AUTH_MAX_BODY_BYTES -- auth-buffering cap; 413 over it
    int maxBodyBytes, String introspectionUrl, String openAccessValidateUrl, String serviceToken
) {
    public GatewaySecurityProperties {
        publicRoutes = publicRoutes == null ? List.of() : List.copyOf(publicRoutes);
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 10_485_760; // 10 MiB default (GATEWAY_AUTH_MAX_BODY_BYTES)
        }
    }
}
