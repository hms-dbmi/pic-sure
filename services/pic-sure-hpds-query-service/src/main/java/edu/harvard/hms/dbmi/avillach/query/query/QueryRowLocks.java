package edu.harvard.hms.dbmi.avillach.query.query;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.QueryRowLockProperties;

/**
 * Runs work on a query row while no other request in this service works on the same row.
 *
 * <p>Work that reads a row, decides from it, calls HPDS and writes the row back takes the lock of that row's id for all of those steps. Two
 * requests for one row then run one after the other, and the second sees everything the first stored. The caller's consents must be looked
 * up inside the lock for the same reason: the request that runs second then works with the latest answer.
 *
 * <p>Every row id has its own lock, so requests for different rows never wait for each other. A lock exists only while a thread holds it or
 * waits for it and is dropped when the last of them leaves, so the number of locks follows the requests in flight and not the rows ever
 * seen. The lock is reentrant: a thread that holds the lock of a row can take it again without waiting.
 *
 * <p>The locks live in the memory of this process. They serialize work on a row only while a single instance of this service runs in an
 * environment.
 */
@Component
public class QueryRowLocks {

    private static final Logger logger = LoggerFactory.getLogger(QueryRowLocks.class);
    private static final String BUSY_ERROR_TYPE = "service_unavailable";
    private static final String BUSY_MESSAGE = "Another request for this query is still in progress. Try again shortly.";

    private final ConcurrentHashMap<UUID, RowLock> rowLocks = new ConcurrentHashMap<>();
    private final long waitSeconds;

    public QueryRowLocks(QueryRowLockProperties properties) {
        this.waitSeconds = properties.getWaitSec();
    }

    /**
     * Runs the work while holding the lock of a row, and releases the lock when the work returns or throws.
     *
     * <p>A thread that finds the lock held by another thread waits for it, for at most the configured
     * {@code picsure.query.row-lock.wait-sec} seconds. A thread that already holds the lock of the row runs the work at once.
     *
     * @param rowId the id of the query row the work reads or writes
     * @param work what to run while the lock is held
     * @param <T> the type of the work's result
     * @return what the work returned
     * @throws PicsureException 503 {@code service_unavailable} with a fixed message when the lock does not become free within the
     *         configured wait, or when the thread is interrupted while it waits; the work has not run, and the thread stays interrupted
     * @throws NullPointerException when the row id or the work is null
     */
    public <T> T withLock(UUID rowId, Supplier<T> work) {
        Objects.requireNonNull(rowId, "rowId is null");
        Objects.requireNonNull(work, "work is null");
        RowLock rowLock = rowLocks.compute(rowId, (id, inUse) -> inUse == null ? new RowLock() : inUse.addUser());
        try {
            if (!acquire(rowLock.lock)) {
                logger.warn("Gave up waiting for the lock of query row {}", rowId);
                throw new PicsureException(HttpStatus.SERVICE_UNAVAILABLE, BUSY_ERROR_TYPE, BUSY_MESSAGE);
            }
            try {
                return work.get();
            } finally {
                rowLock.lock.unlock();
            }
        } finally {
            rowLocks.compute(rowId, (id, inUse) -> inUse.dropUser() ? null : inUse);
        }
    }

    /** Returns the number of row ids that have a lock right now, which is the number of rows some thread holds or waits for. */
    int rowsInUse() {
        return rowLocks.size();
    }

    private boolean acquire(ReentrantLock lock) {
        try {
            return lock.tryLock(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The lock of one row and the number of uses that hold it or wait for it. The count is read and changed only inside a computation of
     * the map entry, which runs one at a time per row id.
     */
    private static final class RowLock {

        private final ReentrantLock lock = new ReentrantLock();
        private int users = 1;

        RowLock addUser() {
            users++;
            return this;
        }

        boolean dropUser() {
            users--;
            return users == 0;
        }
    }
}
