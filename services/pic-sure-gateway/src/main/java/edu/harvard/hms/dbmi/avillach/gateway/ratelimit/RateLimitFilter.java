package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.io.IOException;
import java.util.List;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
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

    protected final ClientIpResolver clientIps;
    protected final List<CidrRange> allowlist;
    private final boolean enabled;
    private final RateLimitStore store;

    RateLimitFilter(RateLimitProperties props, ClientIpResolver clientIps, RateLimitStore store) {
        this.enabled = props.enabled();
        this.clientIps = clientIps;
        this.allowlist = props.platformAllowlistedSourceCidrs().stream().map(CidrRange::parse).toList();
        this.store = store;
    }

    /** The bucket this request draws from, or null when this stage doesn't limit it. */
    protected abstract RateLimitSubject subject(HttpServletRequest req);

    @Override
    protected final void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        RateLimitSubject subject = enabled ? subject(req) : null;
        if (subject == null) {
            chain.doFilter(req, resp);
            return;
        }
        RateLimitDecision decision = store.tryConsume(subject);
        if (!decision.allowed()) {
            RateLimitBreach.reject(req, resp, subject, decision);
            return;
        }
        chain.doFilter(req, resp);
    }
}
