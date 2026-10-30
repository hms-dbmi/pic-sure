package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.bucket4j.Bucket;

/**
 * The store's cache drops every bucket at midnight UTC, which hides whether Bucket4j's own aligned refill works. These tests hold one
 * bucket across days so the refill itself is what resets the windows.
 */
class CalendarBucketTest {

    private final FakeClock clock = new FakeClock("2026-10-29T10:00:30Z");

    private static void consume(Bucket bucket, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(bucket.tryConsume(1)).as("request %d", i + 1).isTrue();
        }
    }

    @Test
    void dailyWindowRefillsExactlyAtEachMidnightUtc() {
        Bucket bucket = InMemoryRateLimitStore.calendarBucket(100, 3, clock);
        consume(bucket, 3);
        assertThat(bucket.tryConsume(1)).isFalse();

        clock.set("2026-10-29T23:59:59.999999999Z");
        assertThat(bucket.tryConsume(1)).isFalse();
        clock.set("2026-10-30T00:00:00Z");
        consume(bucket, 3);
        assertThat(bucket.tryConsume(1)).isFalse();

        clock.set("2026-11-02T08:15:40Z");
        consume(bucket, 3);
        assertThat(bucket.tryConsume(1)).isFalse();
        clock.set("2026-11-02T23:59:59.999999999Z");
        assertThat(bucket.tryConsume(1)).isFalse();
        clock.set("2026-11-03T00:00:00Z");
        consume(bucket, 3);
    }

    @Test
    void burstWindowStaysOnTheMinuteBoundaryDaysLater() {
        Bucket bucket = InMemoryRateLimitStore.calendarBucket(2, 1000, clock);
        consume(bucket, 1);

        clock.set("2026-11-02T08:15:40Z");
        consume(bucket, 2);
        assertThat(bucket.tryConsumeAndReturnRemaining(1).getNanosToWaitForRefill()).isEqualTo(20_000_000_000L);
        clock.set("2026-11-02T08:15:59.999999999Z");
        assertThat(bucket.tryConsume(1)).isFalse();
        clock.set("2026-11-02T08:16:00Z");
        consume(bucket, 2);
    }
}
