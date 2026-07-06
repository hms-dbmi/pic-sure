package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthMode;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;

/**
 * Registration gate for the ENTIRE DB-free auth/audit filter chain (buffering, open-access, introspection, body-mutation, token-refresh,
 * identity-propagation, audit). The chain registers whenever the {@link GatewayModeResolver#resolve resolved effective mode} is not
 * {@link GatewayAuthMode#TRANSPARENT} -- i.e. ENFORCE -- and registers NOTHING when TRANSPARENT.
 *
 * <p>All seven filters share this one gate. This closes the prior bug where a bare {@code mode=enforce} registered only two of the seven
 * filters -- which silently dropped buffering, consent-mutation, identity propagation, and audit while claiming to enforce.
 *
 * <p>Effective mode here is resolved identically to {@link GatewayModeResolver}: an explicit {@code picsure.gateway.security.mode} wins;
 * otherwise {@code picsure.gateway.security.auth-enabled} drives it (true → ENFORCE, false → TRANSPARENT). So the deployed production
 * topology ({@code auth-enabled=true}, mode unset) still registers the full chain, exactly as before.
 */
public class GatewayAuthActiveCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        boolean authEnabled = binder.bind("picsure.gateway.security.auth-enabled", Boolean.class).orElse(false);
        GatewayAuthMode explicitMode = binder.bind("picsure.gateway.security.mode", GatewayAuthMode.class).orElse(null);
        return GatewayModeResolver.resolve(explicitMode, authEnabled) != GatewayAuthMode.TRANSPARENT;
    }
}
