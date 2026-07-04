package edu.harvard.hms.dbmi.avillach.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the RAW {@code picsure.gateway.security.mode} tri-state ({@link GatewayAuthMode}) that coexists with -- and does not replace --
 * {@code GatewaySecurityProperties.authEnabled}. Deliberately a small, separate {@code @ConfigurationProperties} record on the SAME prefix
 * (mirrors how {@code ActuatorTokenProperties} and {@code DownstreamHealthProperties} each own a narrow slice of config rather than growing
 * one giant record); Spring happily binds multiple properties classes to overlapping prefixes as long as the property names they claim
 * don't collide, and {@code mode} is new.
 *
 * <p><b>{@code mode} is intentionally NULLABLE.</b> An absent {@code mode} means "not explicitly set" -- a distinct state from an explicit
 * {@code transparent}. {@link GatewayModeResolver#resolve} treats a {@code null} mode as "derive from {@code auth-enabled}" (so today's
 * production {@code auth-enabled=true}/mode-unset topology resolves to ENFORCE), whereas an explicit mode always wins. Do NOT coerce
 * {@code null} to a concrete mode here -- that would erase the "unset" signal the resolver depends on. Callers wanting the effective mode
 * must go through {@link GatewayModeResolver}, never {@link #getMode()} directly.
 *
 * <p>Deliberately kept to its single canonical constructor: Spring Boot's binder only performs implicit constructor-binding when a
 * {@code @ConfigurationProperties} type has exactly one constructor -- a second (e.g. a no-arg convenience constructor) makes it fall back
 * to JavaBean setter-binding, which fails for records (they have no setters).
 */
@ConfigurationProperties(prefix = "picsure.gateway.security")
public record GatewayAuthProperties(GatewayAuthMode mode) {

    /**
     * Alias for {@link #mode()} -- the raw, possibly-{@code null} configured mode. Prefer {@link GatewayModeResolver} for the effective
     * mode.
     */
    public GatewayAuthMode getMode() {
        return mode;
    }
}
