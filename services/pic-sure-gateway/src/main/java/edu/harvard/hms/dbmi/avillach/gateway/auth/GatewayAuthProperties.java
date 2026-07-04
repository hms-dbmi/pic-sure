package edu.harvard.hms.dbmi.avillach.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code picsure.gateway.security.mode}, the parity-verification tri-state ({@link GatewayAuthMode}) that coexists with -- and does
 * not replace -- {@code GatewaySecurityProperties.authEnabled}. Deliberately a small, separate {@code @ConfigurationProperties} record on
 * the SAME prefix (mirrors how {@code ActuatorTokenProperties} and {@code DownstreamHealthProperties} each own a narrow slice of config
 * rather than growing one giant record); Spring happily binds multiple properties classes to overlapping prefixes as long as the property
 * names they claim don't collide, and {@code mode} is new. Defaults to {@link GatewayAuthMode#TRANSPARENT} so the gateway stays a pure
 * pass-through until a later task wires {@link #getMode()} into the Phase-2 filter chain.
 *
 * <p>Deliberately kept to its single canonical constructor: Spring Boot's binder only performs implicit constructor-binding when a
 * {@code @ConfigurationProperties} type has exactly one constructor -- a second (e.g. a no-arg convenience constructor) makes it fall back
 * to JavaBean setter-binding, which fails for records (they have no setters). Use
 * {@code new GatewayAuthProperties(GatewayAuthMode.TRANSPARENT)} in tests/callers that need the default explicitly.
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewayAuthProperties(GatewayAuthMode mode) {

    public GatewayAuthProperties {
        if (mode == null) {
            mode = GatewayAuthMode.TRANSPARENT;
        }
    }

    /** Alias for {@link #mode()} -- named for the interface later tasks (Pb) are written against. */
    public GatewayAuthMode getMode() {
        return mode;
    }
}
