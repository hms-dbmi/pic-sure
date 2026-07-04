package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.util.List;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditLoggingFilter;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRouteTable;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Commons audit base + scope-aware skip: during the Phase 2&harr;4 interim, result/signed-url are audited by WildFly, so the gateway must
 * NOT emit for them (avoids double events). Phase 4 empties the scope, at which point the gateway audits them too.
 *
 * <p>Also skips OBSERVE-mode catch-all traffic ({@code modeResolver.observesFor}): that traffic is forwarded unchanged to WildFly, which
 * enforces AND audits it, so the gateway must not double-audit (and has no resolved identity for it anyway). Gateway-owned routes in
 * OBSERVE, and every route in ENFORCE, audit as before.
 */
public class GatewayAuditLoggingFilter extends AuditLoggingFilter {

    private final GatewayAuthScope scope;
    private final GatewayModeResolver modeResolver;

    public GatewayAuditLoggingFilter(
        LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains, GatewayAuthScope scope,
        GatewayModeResolver modeResolver
    ) {
        super(client, routes, audit, skipContains);
        this.scope = scope;
        this.modeResolver = modeResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Additive: skip when the interim scope says WildFly still owns this path, when OBSERVE mode leaves this
        // catch-all request to WildFly, OR when the base class's own conditions (disabled client, OPTIONS,
        // /system/status, /openapi.json, skipContains) say skip.
        if (scope.interimOwnedByWildFly(request.getRequestURI()) || modeResolver.observesFor(request.getRequestURI())) {
            return true;
        }
        return super.shouldNotFilter(request);
    }
}
