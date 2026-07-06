package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code picsure.gateway.security.owned-prefixes}: the list of gateway-owned route surfaces (the non-catch-all routes in
 * {@code application.yml}: {@code /logging}, {@code /dictionary}, {@code /uploader}, {@code /visualization}, {@code /hpds},
 * {@code /configuration}, {@code /dataset}). These paths are served directly by the new backends and have NO WildFly counterpart.
 * Everything else is the legacy catch-all surface forwarded to WildFly.
 *
 * <p>Deliberately a small, separate {@code @ConfigurationProperties} record on the SAME prefix as {@link GatewaySecurityProperties} and
 * {@code GatewayAuthProperties} (Spring binds several properties classes to overlapping prefixes as long as the component names they claim
 * don't collide, and {@code owned-prefixes} is new). The {@link #DEFAULT_OWNED_PREFIXES default list} matches the owned route paths in
 * {@code application.yml}; a drift-guard test asserts every configured non-catch-all route is covered, so adding a route without extending
 * this list fails the build.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record RouteSurfaceProperties(List<String> ownedPrefixes) {

    /** Default gateway-owned prefixes — must stay in lock-step with the non-catch-all routes in {@code application.yml}. */
    public static final List<String> DEFAULT_OWNED_PREFIXES =
        List.of("/logging", "/dictionary", "/uploader", "/visualization", "/hpds", "/configuration", "/dataset");

    public RouteSurfaceProperties {
        ownedPrefixes = (ownedPrefixes == null || ownedPrefixes.isEmpty()) ? DEFAULT_OWNED_PREFIXES : List.copyOf(ownedPrefixes);
    }
}
