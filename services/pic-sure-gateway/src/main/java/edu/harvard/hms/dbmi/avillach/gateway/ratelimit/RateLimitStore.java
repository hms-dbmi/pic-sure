package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

/**
 * Counts requests per {@link RateLimitSubject} in fixed calendar windows: burst per minute, reset on the minute boundary, and sustained per
 * day, reset at midnight UTC. Pre-validation subjects have only the per-minute window. A request is admitted only if every window has room,
 * and then counts against all of them; a rejected request counts against none.
 *
 * <p>Contract for a future distributed implementation (spec §4.4): if the configured remote store is unreachable, {@code tryConsume} must
 * reject. Failing open would silently switch the limiter off with nothing to alert on.
 */
public interface RateLimitStore {

    RateLimitDecision tryConsume(RateLimitSubject subject);
}
