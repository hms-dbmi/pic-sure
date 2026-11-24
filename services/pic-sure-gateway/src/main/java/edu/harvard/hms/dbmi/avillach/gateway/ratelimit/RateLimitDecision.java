package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

/**
 * The result of one {@link RateLimitStore#tryConsume} call. A rejection names the breached window and the {@code Retry-After} to send:
 * whole seconds until that window resets, rounded up, at least 1, and the longer wait when both windows are breached.
 */
public record RateLimitDecision(boolean allowed, Window breached, long retryAfterSeconds) {

    public enum Window {
        BURST, SUSTAINED, BOTH
    }

    static final RateLimitDecision ALLOWED = new RateLimitDecision(true, null, 0);

    static RateLimitDecision rejected(Window breached, long retryAfterSeconds) {
        return new RateLimitDecision(false, breached, retryAfterSeconds);
    }
}
