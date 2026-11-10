package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.QueryRowLockProperties;

/**
 * Covers {@link QueryRowLocks}: one thread at a time per row id, no waiting between different ids, release after a failure, the bounded
 * wait, reentrancy, and that no lock outlives its last user. Threads are lined up with latches and by waiting for a thread to park on the
 * lock, never by sleeping for a fixed time.
 */
class QueryRowLocksTest {

    private static final long PATIENCE_SECONDS = 10;
    private static final UUID ROW = UUID.fromString("7b0f2f0c-1f8e-5c56-8d0a-3f6f0e2b4c11");
    private static final UUID OTHER_ROW = UUID.fromString("2d9a4c1e-6b7f-5e0d-9c3a-1a2b3c4d5e6f");

    QueryRowLocks locks = locksWaiting(30);

    private static QueryRowLocks locksWaiting(int seconds) {
        QueryRowLockProperties properties = new QueryRowLockProperties();
        properties.setWaitSec(seconds);
        return new QueryRowLocks(properties);
    }

    /** Waits until the thread is parked in a timed wait, which is where a thread waiting for a row lock stands. */
    static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                fail("The thread never waited for the lock. Its state is " + thread.getState());
            }
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).as("the latch opened in time").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> FutureTask<T> start(FutureTask<T> task) {
        new Thread(task).start();
        return task;
    }

    private static <T> T resultOf(FutureTask<T> task) throws Exception {
        return task.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void returnsWhatTheWorkReturns() {
        assertThat(locks.withLock(ROW, () -> "done")).isEqualTo("done");
    }

    @Test
    void twoThreadsOnOneIdNeverOverlap() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostInsideAtOnce = new AtomicInteger();
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch firstMayLeave = new CountDownLatch(1);
        CountDownLatch secondIsInside = new CountDownLatch(1);

        FutureTask<String> first = start(new FutureTask<>(() -> locks.withLock(ROW, () -> {
            mostInsideAtOnce.accumulateAndGet(inside.incrementAndGet(), Math::max);
            firstIsInside.countDown();
            await(firstMayLeave);
            inside.decrementAndGet();
            return "first";
        })));
        await(firstIsInside);
        FutureTask<String> second = new FutureTask<>(() -> locks.withLock(ROW, () -> {
            mostInsideAtOnce.accumulateAndGet(inside.incrementAndGet(), Math::max);
            secondIsInside.countDown();
            inside.decrementAndGet();
            return "second";
        }));
        Thread secondThread = new Thread(second);
        secondThread.start();

        awaitParked(secondThread);
        assertThat(secondIsInside.getCount()).as("the second thread is kept out while the first is inside").isEqualTo(1);
        firstMayLeave.countDown();

        assertThat(resultOf(first)).isEqualTo("first");
        assertThat(resultOf(second)).isEqualTo("second");
        assertThat(mostInsideAtOnce.get()).isEqualTo(1);
    }

    @Test
    void twoThreadsOnDifferentIdsCanOverlap() throws Exception {
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch secondIsInside = new CountDownLatch(1);

        FutureTask<String> first = start(new FutureTask<>(() -> locks.withLock(ROW, () -> {
            firstIsInside.countDown();
            await(secondIsInside);
            return "first";
        })));
        await(firstIsInside);
        FutureTask<String> second = start(new FutureTask<>(() -> locks.withLock(OTHER_ROW, () -> {
            secondIsInside.countDown();
            return "second";
        })));

        assertThat(resultOf(second)).isEqualTo("second");
        assertThat(resultOf(first)).isEqualTo("first");
    }

    @Test
    void theLockIsReleasedWhenTheWorkThrows() throws Exception {
        assertThatThrownBy(() -> locks.withLock(ROW, () -> {
            throw new IllegalStateException("the work failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the work failed");

        FutureTask<String> another = start(new FutureTask<>(() -> locks.withLock(ROW, () -> "entered")));

        assertThat(resultOf(another)).isEqualTo("entered");
        assertThat(locks.rowsInUse()).isZero();
    }

    @Test
    void aWaitingThreadGetsTheLockWhenTheWorkOfItsHolderThrows() throws Exception {
        CountDownLatch holderIsInside = new CountDownLatch(1);
        CountDownLatch holderMayFail = new CountDownLatch(1);
        FutureTask<String> holder = start(new FutureTask<>(() -> locks.withLock(ROW, () -> {
            holderIsInside.countDown();
            await(holderMayFail);
            throw new IllegalStateException("the work failed");
        })));
        await(holderIsInside);
        FutureTask<String> waiter = new FutureTask<>(() -> locks.withLock(ROW, () -> "entered"));
        Thread waiterThread = new Thread(waiter);
        waiterThread.start();
        awaitParked(waiterThread);

        holderMayFail.countDown();

        assertThatThrownBy(() -> resultOf(holder)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(resultOf(waiter)).isEqualTo("entered");
        assertThat(locks.rowsInUse()).isZero();
    }

    @Test
    void aWaitThatExpiresIsA503ThatNamesNoRow() throws Exception {
        QueryRowLocks impatient = locksWaiting(0);
        CountDownLatch holderIsInside = new CountDownLatch(1);
        CountDownLatch holderMayLeave = new CountDownLatch(1);
        FutureTask<String> holder = start(new FutureTask<>(() -> impatient.withLock(ROW, () -> {
            holderIsInside.countDown();
            await(holderMayLeave);
            return "held";
        })));
        await(holderIsInside);
        AtomicInteger ran = new AtomicInteger();

        assertThatThrownBy(() -> impatient.withLock(ROW, ran::incrementAndGet)).isInstanceOfSatisfying(PicsureException.class, busy -> {
            assertThat(busy.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(busy.getErrorType()).isEqualTo("service_unavailable");
            assertThat(busy.getMessage()).isEqualTo("Another request for this query is still in progress. Try again shortly.")
                .doesNotContain(ROW.toString());
        });

        assertThat(ran.get()).as("the work never ran").isZero();
        holderMayLeave.countDown();
        assertThat(resultOf(holder)).isEqualTo("held");
        assertThat(impatient.rowsInUse()).isZero();
    }

    @Test
    void aThreadInterruptedWhileItWaitsGetsThe503AndStaysInterrupted() throws Exception {
        CountDownLatch holderIsInside = new CountDownLatch(1);
        CountDownLatch holderMayLeave = new CountDownLatch(1);
        FutureTask<String> holder = start(new FutureTask<>(() -> locks.withLock(ROW, () -> {
            holderIsInside.countDown();
            await(holderMayLeave);
            return "held";
        })));
        await(holderIsInside);
        FutureTask<Boolean> waiter = new FutureTask<>(() -> {
            assertThatThrownBy(() -> locks.withLock(ROW, () -> "never")).isInstanceOfSatisfying(
                PicsureException.class, busy -> assertThat(busy.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            );
            return Thread.currentThread().isInterrupted();
        });
        Thread waiterThread = new Thread(waiter);
        waiterThread.start();
        awaitParked(waiterThread);

        waiterThread.interrupt();

        assertThat(resultOf(waiter)).as("the interrupt flag is restored").isTrue();
        holderMayLeave.countDown();
        assertThat(resultOf(holder)).isEqualTo("held");
        assertThat(locks.rowsInUse()).isZero();
    }

    @Test
    void theSameThreadCanTakeTheLockOfOneIdAgain() {
        QueryRowLocks impatient = locksWaiting(0);

        String result = impatient.withLock(ROW, () -> impatient.withLock(ROW, () -> {
            assertThat(impatient.rowsInUse()).isEqualTo(1);
            return "inner";
        }));

        assertThat(result).isEqualTo("inner");
        assertThat(impatient.rowsInUse()).isZero();
    }

    @Test
    void theLockStaysHeldAfterAnInnerUseOfTheSameIdEnds() throws Exception {
        CountDownLatch innerUseEnded = new CountDownLatch(1);
        CountDownLatch outerMayLeave = new CountDownLatch(1);
        CountDownLatch secondIsInside = new CountDownLatch(1);
        FutureTask<String> outer = start(new FutureTask<>(() -> locks.withLock(ROW, () -> {
            locks.withLock(ROW, () -> "inner");
            innerUseEnded.countDown();
            await(outerMayLeave);
            return "outer";
        })));
        await(innerUseEnded);
        FutureTask<String> second = new FutureTask<>(() -> locks.withLock(ROW, () -> {
            secondIsInside.countDown();
            return "second";
        }));
        Thread secondThread = new Thread(second);
        secondThread.start();

        awaitParked(secondThread);
        assertThat(secondIsInside.getCount()).isEqualTo(1);
        outerMayLeave.countDown();

        assertThat(resultOf(outer)).isEqualTo("outer");
        assertThat(resultOf(second)).isEqualTo("second");
    }

    @Test
    void noLockIsKeptOnceNoThreadHoldsOrWaitsForIt() throws Exception {
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch firstMayLeave = new CountDownLatch(1);
        FutureTask<String> first = start(new FutureTask<>(() -> locks.withLock(ROW, () -> {
            firstIsInside.countDown();
            await(firstMayLeave);
            return "first";
        })));
        await(firstIsInside);
        FutureTask<String> second = new FutureTask<>(() -> locks.withLock(ROW, () -> "second"));
        Thread secondThread = new Thread(second);
        secondThread.start();
        awaitParked(secondThread);

        assertThat(locks.rowsInUse()).as("one lock for the holder and the waiter").isEqualTo(1);
        locks.withLock(OTHER_ROW, () -> "other");
        assertThat(locks.rowsInUse()).as("the other row's lock is gone as soon as it is released").isEqualTo(1);
        firstMayLeave.countDown();

        assertThat(resultOf(first)).isEqualTo("first");
        assertThat(resultOf(second)).isEqualTo("second");
        assertThat(locks.rowsInUse()).isZero();
    }

    @Test
    void aNullRowIdIsRefused() {
        assertThatThrownBy(() -> locks.withLock(null, () -> "never")).isInstanceOf(NullPointerException.class);
    }
}
