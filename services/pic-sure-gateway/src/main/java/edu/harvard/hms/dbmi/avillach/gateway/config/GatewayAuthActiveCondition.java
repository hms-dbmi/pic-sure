package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthMode;

/**
 * Registration gate for the two DB-free auth filters ({@code OpenAccessFilter}, {@code PsamaIntrospectionFilter}) that must run in BOTH the
 * deployed enforce path ({@code picsure.gateway.security.auth-enabled=true}, unchanged) and the new, additive parity-verification shadow
 * path ({@code picsure.gateway.security.mode != TRANSPARENT}, e.g. OBSERVE). See {@link GatewayAuthMode} for the documented
 * mode&lt;-&gt;auth-enabled precedence: {@code auth-enabled=true} OR {@code mode=ENFORCE} means "enforce" (byte-identical Phase-2
 * behavior); {@code mode=OBSERVE} (with {@code auth-enabled=false}) means "build the would-be request, shadow-log it, forward unchanged";
 * otherwise ({@code auth-enabled=false} and {@code mode=TRANSPARENT}, the default) the filters are not registered at all -- pure
 * pass-through, exactly as before this class existed.
 *
 * <p>Deliberately does NOT gate {@code BufferingFilter}, {@code BodyMutationFilter}, {@code TokenRefreshResponseFilter}, or
 * {@code IdentityPropagationFilter} -- those remain conditioned on {@code auth-enabled=true} only (unchanged), so OBSERVE mode never
 * buffers (avoiding any 413 side effect on real traffic from a size-capped body read WildFly would otherwise have received untouched),
 * never mutates the body, and never sets identity headers, matching the "purely additive, side-effect-free" requirement for OBSERVE.
 */
class GatewayAuthActiveCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        boolean authEnabled = binder.bind("picsure.gateway.security.auth-enabled", Boolean.class).orElse(false);
        GatewayAuthMode mode = binder.bind("picsure.gateway.security.mode", GatewayAuthMode.class).orElse(GatewayAuthMode.TRANSPARENT);
        return authEnabled || mode != GatewayAuthMode.TRANSPARENT;
    }
}
