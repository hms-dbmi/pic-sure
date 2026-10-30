package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.util.UUID;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Stage 2 of the open-access rate limiter: limits each open-access request {@code OpenAccessFilter} granted, by the key identity PSAMA
 * verified (read from {@link OpenAccessFilter#ATTR_OPEN_ACCESS_VALIDATION}, never from request headers). A USER key draws from its key
 * UUID's bucket and a session from its {@code sub}'s, wherever the request comes from. A PLATFORM key from a source on the platform
 * allowlist is exempt; one from anywhere else, and a grant with no key identity (no key presented, or an older PSAMA), draws from the
 * resolved client IP's bucket (IPv6 by /64). A failed IP resolution is never exempt. Requests that weren't granted open access pass
 * untouched, as does everything when the limiter is disabled.
 */
public class PerKeyRateLimitFilter extends RateLimitFilter {

    public PerKeyRateLimitFilter(RateLimitProperties props, ClientIpResolver clientIps, RateLimitStore store, AuditContext audit) {
        super(props, clientIps, store, audit);
    }

    /** The bucket for a granted request, or null for an exempt one. Identity PSAMA reported incompletely falls back to the client IP. */
    @Override
    protected Target target(HttpServletRequest req) {
        if (!Boolean.TRUE.equals(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED))) {
            return null;
        }
        OpenAccessValidation validation =
            req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION) instanceof OpenAccessValidation v ? v : null;
        String keyType = validation == null ? null : validation.keyType();
        String keyId = validation == null ? null : validation.keyId();
        if (OpenAccessValidation.KEY_TYPE_USER.equals(keyType)) {
            UUID keyUuid = parseUuid(keyId);
            if (keyUuid != null) {
                return new Target(RateLimitSubject.user(keyUuid), null);
            }
        } else if (OpenAccessValidation.KEY_TYPE_SESSION.equals(keyType) && keyId != null && !keyId.isBlank()) {
            return new Target(RateLimitSubject.session(keyId), null);
        }
        ClientIp client = clientIps.resolve(req);
        if (OpenAccessValidation.KEY_TYPE_PLATFORM.equals(keyType) && client.isIn(allowlist)) {
            return null;
        }
        return new Target(RateLimitSubject.ip(client.address()), client);
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
