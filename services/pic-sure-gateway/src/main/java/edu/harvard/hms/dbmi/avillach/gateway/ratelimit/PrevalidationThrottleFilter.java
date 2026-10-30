package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.io.IOException;
import java.util.List;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessRequests;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stage 1 of the open-access rate limiter: a per-client-IP, per-minute throttle on the requests {@code OpenAccessFilter} would send to
 * PSAMA, applied before it does, so a flood of invalid keys can't be relayed to PSAMA. The subject is the resolved client address (IPv6 by
 * /64); the presented key is never consulted, since the caller controls it. A resolved address inside the platform allowlist is exempt; a
 * failed resolution never is. Does nothing when the limiter is disabled.
 */
public class PrevalidationThrottleFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final OpenAccessRequests openAccessRequests;
    private final ClientIpResolver clientIps;
    private final List<CidrRange> allowlist;
    private final RateLimitStore store;

    public PrevalidationThrottleFilter(
        RateLimitProperties props, OpenAccessRequests openAccessRequests, ClientIpResolver clientIps, RateLimitStore store
    ) {
        this.enabled = props.enabled();
        this.openAccessRequests = openAccessRequests;
        this.clientIps = clientIps;
        this.allowlist = props.platformAllowlistedSourceCidrs().stream().map(CidrRange::parse).toList();
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        if (!enabled || !openAccessRequests.validatesWithPsama(req)) {
            chain.doFilter(req, resp);
            return;
        }
        ClientIp client = clientIps.resolve(req);
        if (client.isIn(allowlist)) {
            chain.doFilter(req, resp);
            return;
        }
        RateLimitSubject subject = RateLimitSubject.prevalidationIp(client.address());
        RateLimitDecision decision = store.tryConsume(subject);
        if (!decision.allowed()) {
            RateLimitBreach.reject(req, resp, subject, decision);
            return;
        }
        chain.doFilter(req, resp);
    }
}
