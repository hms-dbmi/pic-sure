package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

/**
 * Fills the {@code result_type} column of the query rows that have none, once after each startup. A row has none when it was written before
 * the column existed, when a stack that does not know the column inserted it, or when it was saved without an owner. The stored body is
 * gzip, which SQL cannot read, so the type is derived here from each row's body with {@link StoredQueryBody#resultTypeOf}.
 *
 * <p>The pass writes the result type and nothing else. It never sets an owner, a general query or the sample flag, and it never changes a
 * result type that is already stored. Each row is written with one guarded statement, {@link QueryRepository#setResultTypeWhereAbsent}, in
 * a transaction of its own. No row is loaded, changed and saved, because a saved entity writes every column back and could undo a status
 * update made in between.
 *
 * <p>The rows are read in pages ordered by id, each page starting after the last id of the one before. A row whose body cannot be read, or
 * names no known result type, is therefore passed once and the pass always reaches the end of the table. Such a row keeps no result type
 * and is looked at again by the pass of the next startup. Because only rows without a result type are read and written, a pass that was cut
 * short is picked up by the next one, and two instances that run the pass at the same time do not disturb each other.
 *
 * <p>The pass runs on a daemon thread of its own that is started when the application is ready, so it delays neither startup nor readiness.
 * When the database cannot be reached the pass logs that and ends, and the service keeps running. There is no retry and no schedule: the
 * next pass is the one of the next startup. Shutdown stops the pass between two rows. The log lines carry row counts only.
 */
@Component
@EnableConfigurationProperties(ResultTypeBackfillProperties.class)
public class ResultTypeBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(ResultTypeBackfill.class);
    private static final String THREAD_NAME = "result-type-backfill";
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    private final QueryRepository queries;
    private final ResultTypeBackfillProperties properties;
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private volatile boolean stopped;

    /**
     * Creates the pass. Nothing runs until {@link #start} or {@link #runPass} is called.
     *
     * @param queries the query rows
     * @param properties whether the pass runs after startup, its page size and its pause between pages
     */
    public ResultTypeBackfill(QueryRepository queries, ResultTypeBackfillProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    /**
     * Starts one pass on a daemon thread and returns at once, without waiting for the pass. Does nothing when the pass is disabled, when
     * {@link #stop} was already called, or when a pass was already started, so an instance runs at most one pass in its lifetime.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            LOG.info("Result type fill is disabled");
            return;
        }
        if (stopped) {
            return;
        }
        Thread thread = Thread.ofPlatform().daemon().name(THREAD_NAME).unstarted(this::runPass);
        if (worker.compareAndSet(null, thread)) {
            thread.start();
        }
    }

    /**
     * Runs one pass on the calling thread: reads every row without a result type once, in pages, and stores the type its body names. The
     * pass ends at the first page that is not full, when a page cannot be read, or when {@link #stop} is called or the calling thread is
     * interrupted during a pause. A failure on one row, whether its body cannot be read or its write is refused, is counted and the pass
     * goes on to the next row. Call it outside a transaction, so that every row is written in a transaction of its own.
     *
     * @return how many rows the pass typed, left, found typed and failed on, and how it ended
     */
    public Summary runPass() {
        Tally tally = new Tally();
        Summary summary = tally.summary(fillPages(tally));
        if (summary.ending() == Ending.READ_FAILED) {
            LOG.warn(
                "Result type fill ended early on a failed read: {} rows typed, {} left without a type, {} already typed, {} failed",
                summary.typed(), summary.leftUntyped(), summary.alreadyTyped(), summary.failed()
            );
        } else {
            LOG.info(
                "Result type fill {}: {} rows typed, {} left without a type, {} already typed, {} failed",
                summary.ending() == Ending.COMPLETE ? "finished" : "was stopped", summary.typed(), summary.leftUntyped(),
                summary.alreadyTyped(), summary.failed()
            );
        }
        return summary;
    }

    /**
     * Stops the pass and waits a few seconds for its thread to end. The thread is interrupted, which ends a pause at once, and the pass
     * looks at no further row. Does nothing more than prevent a later {@link #start} when no pass is running. It runs when the bean is
     * destroyed, which is before the repository and the connections it uses are closed.
     */
    @PreDestroy
    public void stop() {
        stopped = true;
        Thread thread = worker.get();
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(STOP_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tells whether the thread {@link #start} created is still alive.
     *
     * @return true while a started pass has not ended; false when none was started or it has ended
     */
    boolean isRunning() {
        Thread thread = worker.get();
        return thread != null && thread.isAlive();
    }

    private Ending fillPages(Tally tally) {
        UUID lastSeen = null;
        while (!stopped) {
            List<Query> page;
            try {
                page = pageAfter(lastSeen);
            } catch (RuntimeException e) {
                if (stopped) {
                    return Ending.STOPPED;
                }
                LOG.warn("Result type fill could not read a page of rows: {}", e.getClass().getName());
                return Ending.READ_FAILED;
            }
            for (Query row : page) {
                if (stopped) {
                    return Ending.STOPPED;
                }
                tally.count(fill(row));
            }
            if (page.size() < properties.batchSize()) {
                return Ending.COMPLETE;
            }
            lastSeen = page.getLast().getUuid();
            if (!pause()) {
                return Ending.STOPPED;
            }
        }
        return Ending.STOPPED;
    }

    private List<Query> pageAfter(UUID lastSeen) {
        Limit limit = Limit.of(properties.batchSize());
        return lastSeen == null ? queries.findFirstWithoutResultType(limit) : queries.findWithoutResultTypeAfter(lastSeen, limit);
    }

    /**
     * Derives the result type of one row and stores it when the row still has none. Every failure is turned into an outcome, so one row
     * never ends the pass.
     */
    private RowOutcome fill(Query row) {
        try {
            Optional<ResultType> resultType = StoredQueryBody.resultTypeOf(row);
            if (resultType.isEmpty()) {
                return RowOutcome.LEFT_UNTYPED;
            }
            int written = queries.setResultTypeWhereAbsent(row.getUuid(), resultType.get().name());
            return written == 1 ? RowOutcome.TYPED : RowOutcome.ALREADY_TYPED;
        } catch (RuntimeException e) {
            return RowOutcome.FAILED;
        }
    }

    /** Waits the configured pause. Returns false when the wait was interrupted, with the thread's interrupt flag set again. */
    private boolean pause() {
        try {
            Thread.sleep(properties.pause());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** How a pass ended. */
    public enum Ending {

        /** The pass read every row that had no result type. */
        COMPLETE,

        /** The pass was stopped by {@link ResultTypeBackfill#stop}, or its thread was interrupted during a pause. */
        STOPPED,

        /** A page of rows could not be read, for example because the database was unreachable. */
        READ_FAILED
    }

    /**
     * What one pass did.
     *
     * @param typed the rows the pass stored a result type on
     * @param leftUntyped the rows left without a result type because their body is empty, or names no result type or an unknown one
     * @param alreadyTyped the rows that had a result type, or were gone, by the time the pass came to write them
     * @param failed the rows left without a result type because their body could not be read or their write failed
     * @param ending how the pass ended
     */
    public record Summary(long typed, long leftUntyped, long alreadyTyped, long failed, Ending ending) {
    }

    /** What the pass did with one row. Each value is one count of {@link Summary}. */
    private enum RowOutcome {
        TYPED, LEFT_UNTYPED, ALREADY_TYPED, FAILED
    }

    /** The running row counts of one pass. It is used by the one thread that runs the pass. */
    private static final class Tally {

        private final long[] rows = new long[RowOutcome.values().length];

        void count(RowOutcome outcome) {
            rows[outcome.ordinal()]++;
        }

        Summary summary(Ending ending) {
            return new Summary(
                rows[RowOutcome.TYPED.ordinal()], rows[RowOutcome.LEFT_UNTYPED.ordinal()], rows[RowOutcome.ALREADY_TYPED.ordinal()],
                rows[RowOutcome.FAILED.ordinal()], ending
            );
        }
    }
}
