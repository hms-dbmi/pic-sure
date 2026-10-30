package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A rejected request: whose bucket it breached and the store's decision. {@link #reject} is the one way a rate-limit stage answers a
 * breach, so every stage sends the same 429.
 */
public record RateLimitBreach(RateLimitSubject subject, RateLimitDecision decision) {

    /** Request attribute holding the {@link RateLimitBreach}, set before the 429 is written, for the audit event. */
    public static final String REQUEST_ATTRIBUTE = RateLimitBreach.class.getName();

    /** 429 error type. Part of the frontend contract. */
    public static final String ERROR_TYPE = "rate_limited";

    public RateLimitBreach {
        if (decision.allowed()) {
            throw new IllegalArgumentException("not a breach: the decision allowed the request");
        }
    }

    /**
     * Records the breach as {@link #REQUEST_ATTRIBUTE}, then writes {@code 429}, {@code Retry-After} and the standard gateway error body.
     * The response is not reset, so headers set earlier (such as a session refresh) are kept. The caller must not continue the chain.
     */
    public static void reject(HttpServletRequest req, HttpServletResponse resp, RateLimitSubject subject, RateLimitDecision decision)
        throws IOException {
        RateLimitBreach breach = new RateLimitBreach(subject, decision);
        req.setAttribute(REQUEST_ATTRIBUTE, breach);
        long retryAfter = decision.retryAfterSeconds();
        resp.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        GatewayErrors.write(resp, HttpStatus.TOO_MANY_REQUESTS, ERROR_TYPE, "Too many requests. Try again in " + retryAfter + " seconds.");
    }
}
