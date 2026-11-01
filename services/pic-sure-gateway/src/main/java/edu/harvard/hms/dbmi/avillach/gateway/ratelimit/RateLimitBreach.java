package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditLoggingFilter;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A rejected request: whose bucket it breached and the store's decision. {@link #reject} is the one way a rate-limit stage answers a
 * breach, so every stage sends the same 429 and records the same audit event.
 */
public record RateLimitBreach(RateLimitSubject subject, RateLimitDecision decision) {

    /** Request attribute holding the {@link RateLimitBreach}, set before the 429 is written, naming the bucket that refused the request. */
    public static final String REQUEST_ATTRIBUTE = RateLimitBreach.class.getName();

    /** 429 error type. Part of the frontend contract. */
    public static final String ERROR_TYPE = "rate_limited";

    /** Audit {@code auth_action} of a 429. Replaces a stage-2 request's {@code open_access.granted}. */
    public static final String AUDIT_ACTION = "open_access.rate_limited";

    public RateLimitBreach {
        if (decision.allowed()) {
            throw new IllegalArgumentException("not a breach: the decision allowed the request");
        }
    }

    /**
     * Records the breach as {@link #REQUEST_ATTRIBUTE} and in the request's audit event, then writes {@code 429}, {@code Retry-After} and
     * the standard gateway error body. {@code client} is the resolved client an IP subject was built from (its address logged in full, not
     * by the /64 an IPv6 bucket uses) and is ignored for key subjects. The response is not reset, so headers set earlier (such as a session
     * refresh) are kept. The caller must not continue the chain.
     */
    public static void reject(
        HttpServletRequest req, HttpServletResponse resp, AuditContext audit, RateLimitSubject subject, ClientIp client,
        RateLimitDecision decision
    ) throws IOException {
        RateLimitBreach breach = new RateLimitBreach(subject, decision);
        req.setAttribute(REQUEST_ATTRIBUTE, breach);
        breach.audit(req, audit, client);
        long retryAfter = decision.retryAfterSeconds();
        resp.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        GatewayErrors.write(
            resp, HttpStatus.TOO_MANY_REQUESTS, ERROR_TYPE,
            "Too many requests. Try again in " + retryAfter + (retryAfter == 1 ? " second." : " seconds.")
        );
    }

    /**
     * Overwrites the outcome a stage-2 request was given on its grant, so a 429 is never audited as a plain grant, and marks the request
     * audit-required so paths the audit filter otherwise skips still report it. Never records the key or session token: a key subject is
     * its key UUID or session {@code sub}, plus the display prefix PSAMA reported for it. An IP subject whose resolution failed is the
     * connection peer, not the client, so it also gets {@code rate_limit_ip_resolution_failed: true}; the field is absent otherwise.
     */
    private void audit(HttpServletRequest req, AuditContext audit, ClientIp client) {
        req.setAttribute(AuditLoggingFilter.AUDIT_REQUIRED_ATTRIBUTE, Boolean.TRUE);
        audit.put("auth_result", "failure");
        audit.put("auth_action", AUDIT_ACTION);
        audit.put("auth_failure_reason", ERROR_TYPE);
        switch (subject.kind()) {
            case PREVALIDATION_IP, IP -> {
                audit.put("rate_limit_subject_type", "client_ip");
                audit.put("rate_limit_subject", client.address().getHostAddress());
                if (client.resolutionFailed()) {
                    audit.put("rate_limit_ip_resolution_failed", true);
                }
            }
            case USER, SESSION -> {
                audit.put("rate_limit_subject_type", subject.kind() == RateLimitSubject.Kind.USER ? "user_key" : "session");
                audit.put("rate_limit_subject", subject.value());
                if (req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION) instanceof OpenAccessValidation validation) {
                    audit.put("rate_limit_key_display_prefix", validation.displayPrefix());
                }
            }
        }
        audit.put("rate_limit_window", window());
        audit.put("rate_limit_retry_after_seconds", decision.retryAfterSeconds());
    }

    /**
     * Stage 1 has one window. A breach of both stage-2 windows is reported as {@code sustained}, the one its {@code Retry-After} waits out.
     */
    private String window() {
        if (subject.kind() == RateLimitSubject.Kind.PREVALIDATION_IP) {
            return "prevalidation";
        }
        return decision.breached() == RateLimitDecision.Window.BURST ? "burst" : "sustained";
    }
}
