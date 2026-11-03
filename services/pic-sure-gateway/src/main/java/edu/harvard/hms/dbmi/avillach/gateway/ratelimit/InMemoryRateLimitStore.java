package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConfigurationBuilder;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.VerboseResult;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Single-instance {@link RateLimitStore}: one Bucket4j bucket per subject, held by Bucket4j's Caffeine proxy manager. Each bucket's limits
 * refill intervally, aligned to the minute boundary and to midnight UTC, which gives fixed calendar windows; a new bucket starts full.
 * Bucket4j consumes from a multi-limit bucket only when every limit has the token.
 *
 * <p>The cache is capped at {@code max-subjects}. At the cap Caffeine evicts by its size policy, even a live bucket, whose subject comes
 * back with a full allowance; nothing is rejected for capacity. An entry expires once all its limits would be full again: a key or IP entry
 * at the first midnight UTC after its last use, a pre-validation entry (minute limit only) at the next minute boundary. Counters reset on
 * restart.
 *
 * <p>The proxy manager runs each consume inside one atomic {@code compute} on its subject's entry, so two requests never draw from two
 * copies of one subject's bucket, even when the entry expires between them. A request's time is read just before that {@code compute}; a
 * request that read the time before midnight and reaches a bucket that has already refilled after midnight gets no refill from it.
 */
public final class InMemoryRateLimitStore implements RateLimitStore {

    static final String SIZE_METRIC = "gateway.rate_limit.store.subjects";

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final RateLimitProperties props;
    private final TimeMeter clock;
    private final CaffeineProxyManager<RateLimitSubject> buckets;

    public InMemoryRateLimitStore(RateLimitProperties props, MeterRegistry meterRegistry) {
        this(props, meterRegistry, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** {@code clock} drives both the buckets and the cache's expiry, so tests can move time. It must read wall-clock time. */
    InMemoryRateLimitStore(RateLimitProperties props, MeterRegistry meterRegistry, TimeMeter clock) {
        if (!clock.isWallClockBased()) {
            throw new IllegalArgumentException("calendar-aligned windows need a wall-clock TimeMeter");
        }
        this.props = props;
        this.clock = clock;
        // The add-on computes each entry's time to live from the client clock, but Caffeine counts it down with its own ticker. That time
        // is truncated to whole milliseconds, so keep each entry 1 ms past its refill, or an exhausted subject gets a fresh bucket early.
        Caffeine<Object, Object> cache = Caffeine.newBuilder().maximumSize(props.store().maxSubjects()).ticker(clock::currentTimeNanos);
        this.buckets = Bucket4jCaffeine.<RateLimitSubject>builderFor(cache).clientClock(clock)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMillis(1))).build();
        Gauge.builder(SIZE_METRIC, buckets.getCache(), Cache::estimatedSize)
            .description("Rate-limit subjects currently held in the in-memory store").register(meterRegistry);
    }

    @Override
    public RateLimitDecision tryConsume(RateLimitSubject subject) {
        VerboseResult<ConsumptionProbe> result =
            buckets.builder().build(subject, () -> newConfiguration(subject)).asVerbose().tryConsumeAndReturnRemaining(1);
        ConsumptionProbe probe = result.getValue();
        if (probe.isConsumed()) {
            return RateLimitDecision.ALLOWED;
        }
        // Indexes follow the order limits were added in calendarConfiguration: burst first, then sustained if present.
        long[] available = result.getDiagnostics().getAvailableTokensPerEachBandwidth();
        boolean burst = available[0] < 1;
        boolean sustained = available.length > 1 && available[1] < 1;
        Window breached = burst && sustained ? Window.BOTH : sustained ? Window.SUSTAINED : Window.BURST;
        return RateLimitDecision.rejected(breached, retryAfterSeconds(probe.getNanosToWaitForRefill()));
    }

    /** Whole seconds, rounded up, never less than 1. */
    static long retryAfterSeconds(long nanosToWait) {
        return Math.max(1, (nanosToWait + 999_999_999L) / 1_000_000_000L);
    }

    /** Runs pending evictions and expiries; tests call it before reading the size. */
    void cleanUp() {
        cache().cleanUp();
    }

    long size() {
        return cache().estimatedSize();
    }

    private Cache<RateLimitSubject, RemoteBucketState> cache() {
        return buckets.getCache();
    }

    // Runs only when the subject has no entry, so the windows align to the time its bucket is created.
    private BucketConfiguration newConfiguration(RateLimitSubject subject) {
        Instant now = Instant.EPOCH.plusNanos(clock.currentTimeNanos());
        return switch (subject.kind()) {
            case PREVALIDATION_IP -> calendarConfiguration(props.ip().prevalidationPerMinute(), 0, now);
            case USER -> calendarConfiguration(props.user().burstPerMinute(), props.user().sustainedPerDay(), now);
            case SESSION -> calendarConfiguration(props.session().burstPerMinute(), props.session().sustainedPerDay(), now);
            case IP -> calendarConfiguration(props.ip().burstPerMinute(), props.ip().sustainedPerDay(), now);
        };
    }

    /**
     * Limits that reset on the minute boundary and at midnight UTC, the first after {@code now}. {@code perDay} of 0 means no daily limit.
     */
    static BucketConfiguration calendarConfiguration(long perMinute, long perDay, Instant now) {
        ConfigurationBuilder builder = BucketConfiguration.builder()
            .addLimit(limit -> limit.capacity(perMinute).refillIntervallyAligned(perMinute, MINUTE, nextBoundary(now, ChronoUnit.MINUTES)));
        if (perDay > 0) {
            builder.addLimit(limit -> limit.capacity(perDay).refillIntervallyAligned(perDay, DAY, nextBoundary(now, ChronoUnit.DAYS)));
        }
        return builder.build();
    }

    /** The first boundary strictly after {@code now}. {@code Instant} truncation is in UTC, so DAYS gives midnight UTC. */
    private static Instant nextBoundary(Instant now, ChronoUnit unit) {
        return now.truncatedTo(unit).plus(1, unit);
    }
}
