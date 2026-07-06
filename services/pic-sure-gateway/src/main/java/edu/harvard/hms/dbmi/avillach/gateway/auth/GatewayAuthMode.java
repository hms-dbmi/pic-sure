package edu.harvard.hms.dbmi.avillach.gateway.auth;

/**
 * Gateway auth-mode tri-state, bound from {@code picsure.gateway.security.mode} (see {@link GatewayAuthProperties}). This is a NEW,
 * additive knob -- it coexists with (and does not replace) the existing {@code picsure.gateway.security.auth-enabled} boolean in
 * {@link edu.harvard.hms.dbmi.avillach.gateway.config.GatewaySecurityProperties}, which continues to gate the Phase-2 filter chain
 * ({@code BufferingFilter}, {@code OpenAccessFilter}, {@code PsamaIntrospectionFilter}, etc.) exactly as before.
 *
 * <p>{@link GatewayModeResolver} turns the {@code (auth-enabled, mode)} tuple into ONE effective mode and drives the whole filter chain:
 *
 * <ul> <li>{@link #TRANSPARENT}: pure proxy, no gateway auth/audit filters register. Resolved from mode-unset + {@code auth-enabled=false}.
 * <li>{@link #ENFORCE}: full Phase-2 behavior (introspect + mutate + enforce) on EVERY route. Resolved from mode-unset +
 * {@code auth-enabled=true} (today's production topology) or an explicit {@code mode=enforce}. </ul>
 */
public enum GatewayAuthMode {
    TRANSPARENT, ENFORCE
}
