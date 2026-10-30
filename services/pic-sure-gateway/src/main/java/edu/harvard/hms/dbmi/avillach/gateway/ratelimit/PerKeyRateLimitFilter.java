package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stage 2 of the open-access rate limiter: limits each open-access request {@code OpenAccessFilter} granted, by the key identity PSAMA
 * verified (read from {@link OpenAccessFilter#ATTR_OPEN_ACCESS_VALIDATION}, never from request headers). A USER key draws from its key
 * UUID's bucket and a session from its {@code sub}'s, wherever the request comes from. A PLATFORM key from a source on the platform
 * allowlist is exempt; one from anywhere else, and a grant with no key identity (no key presented, or an older PSAMA), draws from the
 * resolved client IP's bucket (IPv6 by /64). A failed IP resolution is never exempt. Requests that weren't granted open access pass
 * untouched, as does everything when the limiter is disabled.
 */
public class PerKeyRateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final ClientIpResolver clientIps;
    private final List<CidrRange> allowlist;
    private final RateLimitStore store;

    public PerKeyRateLimitFilter(RateLimitProperties props, ClientIpResolver clientIps, RateLimitStore store) {
        this.enabled = props.enabled();
        this.clientIps = clientIps;
        this.allowlist = props.platformAllowlistedSourceCidrs().stream().map(CidrRange::parse).toList();
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        if (!enabled || !Boolean.TRUE.equals(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED))) {
            chain.doFilter(req, resp);
            return;
        }
        RateLimitSubject subject = subject(req);
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

    /** The bucket for a granted request, or null for an exempt one. Identity PSAMA reported incompletely falls back to the client IP. */
    private RateLimitSubject subject(HttpServletRequest req) {
        OpenAccessValidation validation =
            req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION) instanceof OpenAccessValidation v ? v : null;
        String keyType = validation == null ? null : validation.keyType();
        String keyId = validation == null ? null : validation.keyId();
        if (OpenAccessValidation.KEY_TYPE_USER.equals(keyType)) {
            UUID keyUuid = parseUuid(keyId);
            if (keyUuid != null) {
                return RateLimitSubject.user(keyUuid);
            }
        } else if (OpenAccessValidation.KEY_TYPE_SESSION.equals(keyType) && keyId != null && !keyId.isBlank()) {
            return RateLimitSubject.session(keyId);
        }
        ClientIp client = clientIps.resolve(req);
        if (OpenAccessValidation.KEY_TYPE_PLATFORM.equals(keyType) && client.isIn(allowlist)) {
            return null;
        }
        return RateLimitSubject.ip(client.address());
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
