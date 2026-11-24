package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import io.github.bucket4j.TimeMeter;

/** A settable wall clock for the store's buckets and cache. */
final class FakeClock implements TimeMeter {

    private final AtomicLong epochNanos = new AtomicLong();

    FakeClock(String instant) {
        set(instant);
    }

    void set(String instant) {
        Instant at = Instant.parse(instant);
        epochNanos.set(at.getEpochSecond() * 1_000_000_000L + at.getNano());
    }

    void advance(Duration duration) {
        epochNanos.addAndGet(duration.toNanos());
    }

    @Override
    public long currentTimeNanos() {
        return epochNanos.get();
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
