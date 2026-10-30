package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.io.IOException;
import java.util.List;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The flow both rate-limit stages share: a request the stage doesn't charge passes, and any other draws from its subject's bucket and gets
 * the standard 429 once that bucket is empty. Everything passes while the limiter is disabled.
 */
abstract class RateLimitFilter extends OncePerRequestFilter {

    /** The subject a request draws from and, for an IP subject, the resolved client it was built from. */
    protected record Target(RateLimitSubject subject, ClientIp client) {
    }

    protected final ClientIpResolver clientIps;
    protected final List<CidrRange> allowlist;
    private final boolean enabled;
    private final RateLimitStore store;
    private final AuditContext audit;

    RateLimitFilter(RateLimitProperties props, ClientIpResolver clientIps, RateLimitStore store, AuditContext audit) {
        this.enabled = props.enabled();
        this.clientIps = clientIps;
        this.allowlist = props.platformAllowlist();
        this.store = store;
        this.audit = audit;
    }

    /** What this request is charged to, or null when this stage doesn't limit it. */
    protected abstract Target target(HttpServletRequest req);

    @Override
    protected final void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        Target target = enabled ? target(req) : null;
        if (target == null) {
            chain.doFilter(req, resp);
            return;
        }
        RateLimitDecision decision = store.tryConsume(target.subject());
        if (!decision.allowed()) {
            RateLimitBreach.reject(req, resp, audit, target.subject(), target.client(), decision);
            return;
        }
        chain.doFilter(req, resp);
    }
}
