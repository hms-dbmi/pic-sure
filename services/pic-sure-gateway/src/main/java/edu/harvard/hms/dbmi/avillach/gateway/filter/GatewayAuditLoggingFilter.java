package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.util.List;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditLoggingFilter;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRouteTable;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Commons audit base + scope-aware skip: during the Phase 2&harr;4 interim, result/signed-url are audited by WildFly, so the gateway must
 * NOT emit for them (avoids double events). Phase 4 empties the scope, at which point the gateway audits them too.
 */
public class GatewayAuditLoggingFilter extends AuditLoggingFilter {

    private final GatewayAuthScope scope;

    public GatewayAuditLoggingFilter(
        LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains, GatewayAuthScope scope
    ) {
        super(client, routes, audit, skipContains);
        this.scope = scope;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Additive: skip when the interim scope says WildFly still owns this path, OR when the base class's own
        // conditions (disabled client, OPTIONS, /system/status, /openapi.json, skipContains) say skip.
        if (scope.interimOwnedByWildFly(request.getRequestURI())) {
            return true;
        }
        return super.shouldNotFilter(request);
    }
}
