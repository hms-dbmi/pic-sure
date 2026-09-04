package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.domain.QueryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueryWaiterTest {

    private static QueryStatus status(String resourceStatus) {
        QueryStatus status = new QueryStatus();
        status.setResourceStatus(resourceStatus);
        status.setResourceResultId(UUID.randomUUID().toString());
        return status;
    }

    private static SyncQueryWaiter waiter(Duration timeout) {
        return new SyncQueryWaiter(Duration.ofMillis(1), Duration.ofMillis(2), timeout);
    }

    @Test
    void returnsImmediatelyWhenAlreadyTerminal() throws Exception {
        QueryStatus available = status("AVAILABLE");
        AtomicInteger polls = new AtomicInteger();

        QueryStatus result = waiter(Duration.ofSeconds(30)).awaitTerminal(available, id -> {
            polls.incrementAndGet();
            return available;
        });

        assertSame(available, result);
        assertEquals(0, polls.get(), "a terminal status must not be re-polled");
    }

    @Test
    void pollsUntilStatusLeavesRunning() throws Exception {
        AtomicInteger polls = new AtomicInteger();

        QueryStatus result = waiter(Duration.ofSeconds(30))
            .awaitTerminal(status("RUNNING"), id -> polls.incrementAndGet() < 3 ? status("PENDING") : status("AVAILABLE"));

        assertEquals("AVAILABLE", result.getResourceStatus());
        assertEquals(3, polls.get());
    }

    @Test
    void abandonsQueryThatNeverCompletes() {
        AtomicInteger polls = new AtomicInteger();

        QueryTimeoutException thrown =
            assertThrows(QueryTimeoutException.class, () -> waiter(Duration.ofMillis(60)).awaitTerminal(status("RUNNING"), id -> {
                polls.incrementAndGet();
                return status("RUNNING");
            }));

        assertTrue(polls.get() > 0, "the waiter must poll at least once before giving up");
        assertTrue(thrown.getMessage().contains("RUNNING"), "the timeout must report the last observed status");
    }

    @Test
    void treatsMissingStatusAsTerminalRatherThanSpinning() throws Exception {
        AtomicInteger polls = new AtomicInteger();

        QueryStatus result = waiter(Duration.ofSeconds(30)).awaitTerminal(status("RUNNING"), id -> {
            polls.incrementAndGet();
            QueryStatus unknown = new QueryStatus();
            unknown.setResourceStatus(null);
            return unknown;
        });

        assertEquals(1, polls.get());
        assertEquals(null, result.getResourceStatus());
    }

    @Test
    void abandonsPollingWhenTheRequestThreadIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                InterruptedException.class, () -> waiter(Duration.ofSeconds(30)).awaitTerminal(status("RUNNING"), id -> status("RUNNING"))
            );
        } finally {
            assertFalse(Thread.interrupted(), "the interrupt flag must be consumed by the sleep, not left set");
        }
    }

    @Test
    void rejectsAPendingStatusWithNoResultIdInsteadOfLoopingForever() {
        QueryStatus noId = new QueryStatus();
        noId.setResourceStatus("RUNNING");

        assertThrows(IllegalStateException.class, () -> waiter(Duration.ofSeconds(30)).awaitTerminal(noId, id -> noId));
    }
}
