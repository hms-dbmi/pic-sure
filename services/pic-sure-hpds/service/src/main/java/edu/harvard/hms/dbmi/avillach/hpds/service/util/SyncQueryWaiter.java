package edu.harvard.hms.dbmi.avillach.hpds.service.util;

import edu.harvard.dbmi.avillach.domain.QueryStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Waits for a synchronous query to leave the {@code RUNNING}/{@code PENDING} states, with a bounded number of polls, an exponential backoff
 * between them, and a hard deadline. Replaces the unbounded zero-delay busy-wait that let one request pin a servlet worker for as long as a
 * query stayed queued.
 */
@Component
public class SyncQueryWaiter {

    private static final String RUNNING = "RUNNING";
    private static final String PENDING = "PENDING";

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final Duration timeout;

    public SyncQueryWaiter(
        @Value("${hpds.query.sync.poll-initial-delay:PT0.01S}") Duration initialDelay,
        @Value("${hpds.query.sync.poll-max-delay:PT1S}") Duration maxDelay, @Value("${hpds.query.sync.timeout:PT5M}") Duration timeout
    ) {
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.timeout = timeout;
    }

    /**
     * Polls {@code statusLookup} until the query reaches a non-pending state.
     *
     * @param initial the status returned when the query was submitted; a {@code null} status, or one whose {@code resourceStatus} is not
     *        {@code RUNNING}/{@code PENDING}, is returned unchanged without any poll
     * @param statusLookup re-reads the status for a query id
     * @return the first status observed that is not {@code RUNNING} or {@code PENDING}
     * @throws QueryTimeoutException if the query is still pending at the deadline
     * @throws InterruptedException if the request thread is interrupted while backing off
     * @throws IllegalStateException if a pending status carries no usable {@code resourceResultId} to poll with
     */
    public QueryStatus awaitTerminal(QueryStatus initial, Function<UUID, QueryStatus> statusLookup) throws InterruptedException {
        QueryStatus status = initial;
        long deadline = System.nanoTime() + timeout.toNanos();
        long delayMillis = Math.max(1L, initialDelay.toMillis());

        while (isPending(status)) {
            UUID queryId = pollableId(status);
            if (System.nanoTime() - deadline >= 0) {
                throw new QueryTimeoutException(status.getResourceResultId(), status.getResourceStatus());
            }
            Thread.sleep(delayMillis);
            delayMillis = Math.min(delayMillis * 2, Math.max(1L, maxDelay.toMillis()));
            status = statusLookup.apply(queryId);
        }

        return status;
    }

    private static boolean isPending(QueryStatus status) {
        if (status == null || status.getResourceStatus() == null) {
            return false;
        }
        String resourceStatus = status.getResourceStatus().toUpperCase(Locale.ENGLISH);
        return RUNNING.equals(resourceStatus) || PENDING.equals(resourceStatus);
    }

    private static UUID pollableId(QueryStatus status) {
        String resourceResultId = status.getResourceResultId();
        if (resourceResultId == null || resourceResultId.isBlank()) {
            throw new IllegalStateException("A " + status.getResourceStatus() + " query has no resourceResultId to poll");
        }
        try {
            return UUID.fromString(resourceResultId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("A " + status.getResourceStatus() + " query has a non-UUID resourceResultId", e);
        }
    }
}
