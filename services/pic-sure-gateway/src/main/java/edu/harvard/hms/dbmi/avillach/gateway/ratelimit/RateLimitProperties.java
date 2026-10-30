package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Open-access rate-limit configuration, bound from {@code picsure.gateway.rate-limit.*}. The defaults and the {@code RATE_LIMIT_*} env var
 * names deployments set live in {@code application.yml}. A zero or negative limit, or {@code enabled=true} with no trusted proxies, throws
 * during binding and stops the context. The two CIDR lists stay raw strings here: they are trimmed and blank entries dropped, but their
 * syntax is checked by the client-IP resolver that consumes them.
 */
@ConfigurationProperties(prefix = "picsure.gateway.rate-limit")
public record RateLimitProperties(
    boolean enabled, KeyLimits user, KeyLimits session, IpLimits ip, Store store, List<String> trustedProxies,
    List<String> platformAllowlistedSourceCidrs
) {

    static final String PREFIX = "picsure.gateway.rate-limit";

    public RateLimitProperties {
        user = required(user, "user");
        session = required(session, "session");
        ip = required(ip, "ip");
        store = required(store, "store");
        user.validate(PREFIX + ".user");
        session.validate(PREFIX + ".session");
        ip.validate();
        store.validate();
        trustedProxies = clean(trustedProxies);
        platformAllowlistedSourceCidrs = clean(platformAllowlistedSourceCidrs);
        if (enabled && trustedProxies.isEmpty()) {
            throw new IllegalArgumentException(
                PREFIX + ".enabled is true but " + PREFIX + ".trusted-proxies (RATE_LIMIT_TRUSTED_PROXIES) is empty. Add the reverse proxy "
                    + "in front of the gateway (httpd): without it every request appears to come from that proxy and all clients share "
                    + "one rate-limit bucket."
            );
        }
    }

    /** Per-minute burst and per-UTC-day sustained limits for one kind of confirmed key. */
    public record KeyLimits(long burstPerMinute, long sustainedPerDay) {
        void validate(String prefix) {
            requirePositive(prefix + ".burst-per-minute", burstPerMinute);
            requirePositive(prefix + ".sustained-per-day", sustainedPerDay);
        }
    }

    /** Limits for client-IP buckets: stage 2's burst and sustained, and stage 1's pre-validation per-minute limit. */
    public record IpLimits(long burstPerMinute, long sustainedPerDay, long prevalidationPerMinute) {
        void validate() {
            requirePositive(PREFIX + ".ip.burst-per-minute", burstPerMinute);
            requirePositive(PREFIX + ".ip.sustained-per-day", sustainedPerDay);
            requirePositive(PREFIX + ".ip.prevalidation-per-minute", prevalidationPerMinute);
        }
    }

    /** {@code maxSubjects} caps the bucket cache; past it the cache evicts, it never rejects. */
    public record Store(long maxSubjects) {
        void validate() {
            requirePositive(PREFIX + ".store.max-subjects", maxSubjects);
        }
    }

    private static <T> T required(T section, String name) {
        if (section == null) {
            throw new IllegalArgumentException(PREFIX + "." + name + " is not configured");
        }
        return section;
    }

    private static void requirePositive(String property, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be a positive integer, got " + value);
        }
    }

    private static List<String> clean(List<String> entries) {
        return entries == null ? List.of() : entries.stream().map(String::trim).filter(entry -> !entry.isEmpty()).toList();
    }
}
