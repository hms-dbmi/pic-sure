package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.VerboseResult;
import io.github.bucket4j.local.LocalBucketBuilder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Single-instance {@link RateLimitStore}: one Bucket4j bucket per subject, held in one Caffeine cache. Each bucket's limits refill
 * intervally, aligned to the minute boundary and to midnight UTC, which gives fixed calendar windows; a new bucket starts full. Bucket4j
 * consumes from a multi-limit bucket only when every limit has the token.
 *
 * <p>The cache is capped at {@code max-subjects}. At the cap Caffeine evicts by its size policy, even a live bucket, whose subject comes
 * back with a full allowance; nothing is rejected for capacity. An entry expires at the first midnight UTC after its last use, when both
 * its windows would be full again anyway. Counters reset on restart.
 *
 * <p>Each request fetches and consumes inside one atomic {@code compute} on its subject's entry, so a bucket is never consumed from after
 * the cache has replaced it. Otherwise a request that fetched a bucket just before it expired could consume from it afterwards while
 * another request drew from its full replacement.
 */
public final class InMemoryRateLimitStore implements RateLimitStore {

    static final String SIZE_METRIC = "gateway.rate_limit.store.subjects";

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final RateLimitProperties props;
    private final TimeMeter clock;
    private final Cache<RateLimitSubject, Bucket> buckets;

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
        this.buckets = Caffeine.newBuilder().maximumSize(props.store().maxSubjects()).expireAfter(new UntilNextUtcMidnight())
            .ticker(clock::currentTimeNanos).build();
        Gauge.builder(SIZE_METRIC, buckets, Cache::estimatedSize).description("Rate-limit subjects currently held in the in-memory store")
            .register(meterRegistry);
    }

    @Override
    public RateLimitDecision tryConsume(RateLimitSubject subject) {
        AtomicReference<VerboseResult<ConsumptionProbe>> consumed = new AtomicReference<>();
        buckets.asMap().compute(subject, (key, bucket) -> {
            Bucket current = bucket == null ? newBucket(key) : bucket;
            consumed.set(current.asVerbose().tryConsumeAndReturnRemaining(1));
            return current;
        });
        VerboseResult<ConsumptionProbe> result = consumed.get();
        ConsumptionProbe probe = result.getValue();
        if (probe.isConsumed()) {
            return RateLimitDecision.ALLOWED;
        }
        // Indexes follow the order limits were added in calendarBucket: burst first, then sustained if present.
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
        buckets.cleanUp();
    }

    long size() {
        return buckets.estimatedSize();
    }

    private Bucket newBucket(RateLimitSubject subject) {
        return switch (subject.kind()) {
            case PREVALIDATION_IP -> calendarBucket(props.ip().prevalidationPerMinute(), 0, clock);
            case USER -> calendarBucket(props.user().burstPerMinute(), props.user().sustainedPerDay(), clock);
            case SESSION -> calendarBucket(props.session().burstPerMinute(), props.session().sustainedPerDay(), clock);
            case IP -> calendarBucket(props.ip().burstPerMinute(), props.ip().sustainedPerDay(), clock);
        };
    }

    /** A full bucket whose windows reset on the minute boundary and at midnight UTC. {@code perDay} of 0 means no sustained window. */
    static Bucket calendarBucket(long perMinute, long perDay, TimeMeter clock) {
        Instant now = now(clock.currentTimeNanos());
        LocalBucketBuilder builder = Bucket.builder().withCustomTimePrecision(clock)
            .addLimit(limit -> limit.capacity(perMinute).refillIntervallyAligned(perMinute, MINUTE, nextBoundary(now, ChronoUnit.MINUTES)));
        if (perDay > 0) {
            builder.addLimit(limit -> limit.capacity(perDay).refillIntervallyAligned(perDay, DAY, nextBoundary(now, ChronoUnit.DAYS)));
        }
        return builder.build();
    }

    private static Instant now(long epochNanos) {
        return Instant.EPOCH.plusNanos(epochNanos);
    }

    /** The first boundary strictly after {@code now}. {@code Instant} truncation is in UTC, so DAYS gives midnight UTC. */
    private static Instant nextBoundary(Instant now, ChronoUnit unit) {
        return now.truncatedTo(unit).plus(1, unit);
    }

    private static final class UntilNextUtcMidnight implements Expiry<RateLimitSubject, Bucket> {

        @Override
        public long expireAfterCreate(RateLimitSubject subject, Bucket bucket, long currentTime) {
            return untilMidnight(currentTime);
        }

        @Override
        public long expireAfterUpdate(RateLimitSubject subject, Bucket bucket, long currentTime, long currentDuration) {
            return untilMidnight(currentTime);
        }

        @Override
        public long expireAfterRead(RateLimitSubject subject, Bucket bucket, long currentTime, long currentDuration) {
            return untilMidnight(currentTime);
        }

        // The cache's ticker is the store's wall clock, so currentTime is nanoseconds since the epoch.
        private static long untilMidnight(long currentTime) {
            Instant now = now(currentTime);
            return Duration.between(now, nextBoundary(now, ChronoUnit.DAYS)).toNanos();
        }
    }
}
