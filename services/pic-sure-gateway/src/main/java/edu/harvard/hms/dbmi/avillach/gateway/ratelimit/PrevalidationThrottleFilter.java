package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessRequests;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Stage 1 of the open-access rate limiter: a per-client-IP, per-minute throttle on the requests {@code OpenAccessFilter} would send to
 * PSAMA, applied before it does, so a flood of invalid keys can't be relayed to PSAMA. The subject is the resolved client address (IPv6 by
 * /64); the presented key is never consulted, since the caller controls it. A resolved address inside the platform allowlist is exempt; a
 * failed resolution never is. Does nothing when the limiter is disabled.
 */
public class PrevalidationThrottleFilter extends RateLimitFilter {

    private final OpenAccessRequests openAccessRequests;

    public PrevalidationThrottleFilter(
        RateLimitProperties props, OpenAccessRequests openAccessRequests, ClientIpResolver clientIps, RateLimitStore store,
        AuditContext audit
    ) {
        super(props, clientIps, store, audit);
        this.openAccessRequests = openAccessRequests;
    }

    @Override
    protected Target target(HttpServletRequest req) {
        if (!openAccessRequests.validatesWithPsama(req)) {
            return null;
        }
        ClientIp client = clientIps.resolve(req);
        return client.isIn(allowlist) ? null : new Target(RateLimitSubject.prevalidationIp(client.address()), client.address());
    }
}
