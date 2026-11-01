package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties.IpLimits;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties.KeyLimits;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties.Store;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Proves the Bucket4j behaviour the limiter relies on (spec §2.4): calendar-aligned windows, both-or-neither consumption and
 * {@code Retry-After}, all on a fake clock.
 */
class InMemoryRateLimitStoreTest {

    private static final RateLimitSubject USER = RateLimitSubject.user(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    private final FakeClock clock = new FakeClock("2026-10-29T10:00:30Z");
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private static RateLimitProperties props(long burst, long sustained, long prevalidation, long maxSubjects) {
        return new RateLimitProperties(
            false, new KeyLimits(burst, sustained), new KeyLimits(burst, sustained), new IpLimits(burst, sustained, prevalidation),
            new Store(maxSubjects), List.of(), List.of()
        );
    }

    private InMemoryRateLimitStore store(long burst, long sustained) {
        return new InMemoryRateLimitStore(props(burst, sustained, burst, 1000), meters, clock);
    }

    private static void consume(RateLimitStore store, RateLimitSubject subject, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(store.tryConsume(subject).allowed()).as("request %d", i + 1).isTrue();
        }
    }

    @Test
    void burstWindowResetsExactlyOnTheMinuteBoundary() {
        InMemoryRateLimitStore store = store(3, 100);
        consume(store, USER, 3);

        assertThat(store.tryConsume(USER)).isEqualTo(RateLimitDecision.rejected(Window.BURST, 30));

        clock.set("2026-10-29T10:00:59.999999999Z");
        assertThat(store.tryConsume(USER)).isEqualTo(RateLimitDecision.rejected(Window.BURST, 1));

        clock.set("2026-10-29T10:01:00Z");
        consume(store, USER, 3);
        assertThat(store.tryConsume(USER).allowed()).isFalse();
    }

    @Test
    void sustainedWindowResetsExactlyAtMidnightUtc() {
        InMemoryRateLimitStore store = store(100, 5);
        consume(store, USER, 5);

        // 10:00:30 to midnight is 13h 59m 30s.
        assertThat(store.tryConsume(USER)).isEqualTo(RateLimitDecision.rejected(Window.SUSTAINED, 50_370));

        clock.set("2026-10-29T23:59:59.999999999Z");
        assertThat(store.tryConsume(USER)).isEqualTo(RateLimitDecision.rejected(Window.SUSTAINED, 1));

        clock.set("2026-10-30T00:00:00Z");
        consume(store, USER, 5);
        assertThat(store.tryConsume(USER).allowed()).isFalse();
    }

    @Test
    void subjectFirstSeenMidDayGetsTheFullDailyAllowance() {
        clock.set("2026-10-29T23:30:00Z");
        InMemoryRateLimitStore store = store(10, 10);

        consume(store, USER, 10);
        assertThat(store.tryConsume(USER).breached()).isEqualTo(Window.BOTH);
    }

    @Test
    void rejectionByTheDailyLimitLeavesTheBurstBudgetUntouched() {
        InMemoryRateLimitStore store = store(3, 4);
        consume(store, USER, 3);
        clock.set("2026-10-29T10:01:00Z");
        consume(store, USER, 1); // daily allowance spent, two burst tokens left this minute

        for (int i = 0; i < 10; i++) {
            assertThat(store.tryConsume(USER).breached()).isEqualTo(Window.SUSTAINED);
        }
    }

    @Test
    void rejectionByTheBurstLimitLeavesTheDailyBudgetUntouched() {
        InMemoryRateLimitStore store = store(2, 5);
        consume(store, USER, 2);
        for (int i = 0; i < 10; i++) {
            assertThat(store.tryConsume(USER).breached()).isEqualTo(Window.BURST);
        }

        clock.set("2026-10-29T10:01:00Z");
        consume(store, USER, 2);
        clock.set("2026-10-29T10:02:00Z");
        consume(store, USER, 1);
        assertThat(store.tryConsume(USER).breached()).isEqualTo(Window.SUSTAINED);
    }

    @Test
    void bothBreachedReportsTheLongerWait() {
        InMemoryRateLimitStore store = store(2, 2);
        consume(store, USER, 2);

        assertThat(store.tryConsume(USER)).isEqualTo(RateLimitDecision.rejected(Window.BOTH, 50_370));
    }

    @Test
    void retryAfterRoundsUpWithAOneSecondFloor() {
        InMemoryRateLimitStore store = store(1, 100);
        consume(store, USER, 1);

        clock.set("2026-10-29T10:00:58.2Z");
        assertThat(store.tryConsume(USER).retryAfterSeconds()).isEqualTo(2);

        assertThat(InMemoryRateLimitStore.retryAfterSeconds(0)).isEqualTo(1);
        assertThat(InMemoryRateLimitStore.retryAfterSeconds(1)).isEqualTo(1);
        assertThat(InMemoryRateLimitStore.retryAfterSeconds(1_000_000_000L)).isEqualTo(1);
        assertThat(InMemoryRateLimitStore.retryAfterSeconds(1_000_000_001L)).isEqualTo(2);
    }

    @Test
    void prevalidationBucketsHaveOnlyThePerMinuteLimit() throws Exception {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(props(1, 1, 3, 1000), meters, clock);
        RateLimitSubject subject = RateLimitSubject.prevalidationIp(InetAddress.getByName("203.0.113.7"));

        for (int minute = 0; minute < 5; minute++) {
            clock.set("2026-10-29T10:0" + (minute + 1) + ":00Z");
            consume(store, subject, 3);
            assertThat(store.tryConsume(subject)).isEqualTo(RateLimitDecision.rejected(Window.BURST, 60));
        }
    }

    @Test
    void eachKindUsesItsOwnLimits() throws Exception {
        RateLimitProperties props = new RateLimitProperties(
            false, new KeyLimits(1, 100), new KeyLimits(2, 100), new IpLimits(3, 100, 4), new Store(1000), List.of(), List.of()
        );
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(props, meters, clock);
        InetAddress address = InetAddress.getByName("203.0.113.7");

        consume(store, USER, 1);
        consume(store, RateLimitSubject.session("s"), 2);
        consume(store, RateLimitSubject.ip(address), 3);
        consume(store, RateLimitSubject.prevalidationIp(address), 4);

        assertThat(store.tryConsume(USER).allowed()).isFalse();
        assertThat(store.tryConsume(RateLimitSubject.session("s")).allowed()).isFalse();
        assertThat(store.tryConsume(RateLimitSubject.ip(address)).allowed()).isFalse();
        assertThat(store.tryConsume(RateLimitSubject.prevalidationIp(address)).allowed()).isFalse();
    }

    @Test
    void sameRawValueDifferentKindsHaveIndependentBuckets() throws Exception {
        InMemoryRateLimitStore store = store(2, 100);
        InetAddress address = InetAddress.getByName("203.0.113.7");
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");

        consume(store, RateLimitSubject.ip(address), 2);
        assertThat(store.tryConsume(RateLimitSubject.ip(address)).allowed()).isFalse();
        consume(store, RateLimitSubject.prevalidationIp(address), 2);

        consume(store, RateLimitSubject.user(id), 2);
        assertThat(store.tryConsume(RateLimitSubject.user(id)).allowed()).isFalse();
        consume(store, RateLimitSubject.session(id.toString()), 2);
    }

    @Test
    void ipv6ClientsInOneSlash64ShareABucket() throws Exception {
        InMemoryRateLimitStore store = store(2, 100);

        consume(store, RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:2::1")), 2);

        assertThat(store.tryConsume(RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:2::99"))).allowed()).isFalse();
        assertThat(store.tryConsume(RateLimitSubject.ip(InetAddress.getByName("2001:db8:1:3::1"))).allowed()).isTrue();
    }

    @Test
    void concurrentConsumptionNeverExceedsTheLimit() throws Exception {
        InMemoryRateLimitStore store = store(50, 1000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    start.await();
                    int allowed = 0;
                    for (int i = 0; i < 100; i++) {
                        if (store.tryConsume(USER).allowed()) {
                            allowed++;
                        }
                    }
                    return allowed;
                });
            }
            List<Future<Integer>> results = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                results.add(pool.submit(task));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get();
            }
            assertThat(total).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fillingPastMaxSubjectsAdmitsNewSubjectsAndStaysBounded() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(props(1, 1, 1, 10), meters, clock);

        for (int i = 0; i < 500; i++) {
            assertThat(store.tryConsume(RateLimitSubject.session("s" + i)).allowed()).isTrue();
        }
        store.cleanUp();

        assertThat(store.size()).isLessThanOrEqualTo(10);
        assertThat(meters.get(InMemoryRateLimitStore.SIZE_METRIC).gauge().value()).isEqualTo(store.size());
    }

    @Test
    void entriesExpireAtTheFirstMidnightUtcAfterTheirLastUse() {
        InMemoryRateLimitStore store = store(10, 10);
        store.tryConsume(USER);
        clock.set("2026-10-30T00:30:00Z");
        store.tryConsume(USER); // used again after midnight: lives until the next midnight

        clock.set("2026-10-30T23:59:59Z");
        store.cleanUp();
        assertThat(store.size()).isEqualTo(1);

        clock.set("2026-10-31T00:00:00Z");
        store.cleanUp();
        assertThat(store.size()).isZero();
    }

    /**
     * A request fetches yesterday's bucket just before midnight and is held before consuming from it, while another request arrives after
     * midnight. They must not draw from two buckets, so the new day admits only one of them.
     */
    @Test
    void requestHoldingYesterdaysBucketAcrossMidnightAndANewRequestAdmitOnlyOne() throws Exception {
        GatedClock gated = new GatedClock(clock);
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(props(1, 1, 1, 1000), meters, gated);
        clock.set("2026-10-29T23:00:00Z");
        consume(store, USER, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            clock.set("2026-10-29T23:59:59.999Z");
            Future<RateLimitDecision> held = pool.submit(() -> {
                gated.gateThisThread();
                return store.tryConsume(USER);
            });
            assertThat(gated.awaitHeld()).as("held request reached its bucket").isTrue();

            clock.set("2026-10-30T00:00:00.001Z");
            Future<RateLimitDecision> afterMidnight = pool.submit(() -> store.tryConsume(USER));
            try {
                afterMidnight.get(200, TimeUnit.MILLISECONDS); // a store that let it run in between would answer now
            } catch (TimeoutException waitingForTheHeldRequest) {
                // the store serialises the two requests on the subject's entry
            }
            gated.release();

            List<Boolean> allowed = List.of(held.get(10, TimeUnit.SECONDS).allowed(), afterMidnight.get(10, TimeUnit.SECONDS).allowed());
            assertThat(allowed).as("held, after midnight").containsOnlyOnce(true);
        } finally {
            gated.release();
            pool.shutdownNow();
        }
    }

    @Test
    void refusesANonWallClock() {
        assertThatThrownBy(() -> new InMemoryRateLimitStore(props(1, 1, 1, 1), meters, TimeMeter.SYSTEM_NANOTIME))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Wraps the fake clock so that one thread blocks the first time Bucket4j asks it for the time, which happens only after the store has
     * fetched that thread's bucket from the cache. The test can then move time and send other requests between fetch and consume.
     */
    private static final class GatedClock implements TimeMeter {

        private final FakeClock delegate;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile Thread gated;

        GatedClock(FakeClock delegate) {
            this.delegate = delegate;
        }

        void gateThisThread() {
            gated = Thread.currentThread();
        }

        boolean awaitHeld() throws InterruptedException {
            return held.await(10, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public long currentTimeNanos() {
            if (Thread.currentThread() == gated && calledFromBucket4j()) {
                gated = null;
                held.countDown();
                try {
                    released.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.currentTimeNanos();
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }

        private static boolean calledFromBucket4j() {
            return StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().startsWith("io.github.bucket4j.")));
        }
    }
}
