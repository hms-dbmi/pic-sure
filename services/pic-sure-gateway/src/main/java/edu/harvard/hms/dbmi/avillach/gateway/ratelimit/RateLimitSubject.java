package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Whose bucket a request draws from: a kind (which stage, and what the value identifies) plus the value. The kind is part of the identity,
 * so the same address has separate pre-validation and post-grant buckets, and a key UUID never collides with a session id. IP subjects are
 * the full address for IPv4 and the /64 prefix for IPv6. Build subjects with the factory methods, which normalise the value.
 */
public record RateLimitSubject(Kind kind, String value) {

    public enum Kind {
        /** Stage 1: resolved client IP, before any key is validated. Per-minute limit only. */
        PREVALIDATION_IP,
        /** Stage 2: a confirmed USER key, by key UUID. */
        USER,
        /** Stage 2: an open-access session, by the session token's {@code sub}. */
        SESSION,
        /** Stage 2: resolved client IP, for valid requests with no key or a PLATFORM key from a non-allowlisted source. */
        IP
    }

    public RateLimitSubject {
        Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("rate-limit subject value must not be blank");
        }
    }

    public static RateLimitSubject prevalidationIp(InetAddress address) {
        return new RateLimitSubject(Kind.PREVALIDATION_IP, ipValue(address));
    }

    public static RateLimitSubject user(UUID keyId) {
        return new RateLimitSubject(Kind.USER, keyId.toString());
    }

    public static RateLimitSubject session(String sessionId) {
        return new RateLimitSubject(Kind.SESSION, sessionId);
    }

    public static RateLimitSubject ip(InetAddress address) {
        return new RateLimitSubject(Kind.IP, ipValue(address));
    }

    private static String ipValue(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        byte[] prefix = Arrays.copyOf(address.getAddress(), 16);
        Arrays.fill(prefix, 8, 16, (byte) 0);
        try {
            // getByAddress drops any scope id, so link-local addresses on different interfaces still share a prefix.
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            throw new IllegalStateException("16-byte address rejected", e);
        }
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + value;
    }
}
