package edu.harvard.hms.dbmi.avillach.gateway.auth;

/**
 * Gateway auth-mode tri-state for the parity-verification pipeline, bound from {@code picsure.gateway.security.mode} (see
 * {@link GatewayAuthProperties}). This is a NEW, additive knob -- it coexists with (and does not replace) the existing
 * {@code picsure.gateway.security.auth-enabled} boolean in {@link edu.harvard.hms.dbmi.avillach.gateway.config.GatewaySecurityProperties},
 * which continues to gate the Phase-2 filter chain ({@code BufferingFilter}, {@code OpenAccessFilter}, {@code PsamaIntrospectionFilter},
 * etc.) exactly as before.
 *
 * <p>Mapping intent for the eventual cutover (documented here, NOT wired by this enum or by
 * {@link edu.harvard.hms.dbmi.avillach.gateway.config.SecurityConfig} yet -- a later task wires the branch):
 *
 * <ul> <li>{@link #TRANSPARENT} &lt;-&gt; {@code auth-enabled=false}: pure proxy, no gateway auth/audit filters register.
 * <li>{@link #ENFORCE} &lt;-&gt; {@code auth-enabled=true}: current Phase-2 behavior (introspect + mutate + enforce). <li>{@link #OBSERVE}:
 * NEW. Build the introspection request the gateway *would* send, emit a {@code picsure.shadow} record (side=GW), and forward the request
 * UNCHANGED -- no PSAMA call, no body mutation -- so WildFly remains the sole enforcer while the two sides' requests are compared
 * out-of-band by a standalone reconciler. </ul>
 */
public enum GatewayAuthMode {
    TRANSPARENT, OBSERVE, ENFORCE
}
