package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.transaction.PlatformTransactionManager;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;

/**
 * Saves two requests onto one stored row from two threads released together by a barrier. The service runs on the clock the test passes, so
 * a fixed clock makes both saves read the same time, and only the order the saves take the row in can tell their runs apart.
 */
final class RacingResaves {

    private static final int RACERS = 2;

    private RacingResaves() {}

    /**
     * Runs both saves at once, outside any transaction of the caller.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param transactionManager the transaction manager both saves open their own transactions on
     * @param clock the clock both saves read
     * @param first the request one thread sends
     * @param second the request the other thread sends
     * @return how the save of the first request ended, then how the save of the second ended
     * @throws Exception when a save does not finish in time
     */
    static List<SaveOutcome> run(
        QueryRepository queries, QueryRunRepository runs, PlatformTransactionManager transactionManager, Clock clock,
        SaveQueryRequest first, SaveQueryRequest second
    ) throws Exception {
        CyclicBarrier released = new CyclicBarrier(RACERS);
        QueryPersistenceService service = new QueryPersistenceService(queries, runs, transactionManager, clock);

        try (var executor = Executors.newFixedThreadPool(RACERS)) {
            Future<SaveOutcome> firstSave = executor.submit(() -> {
                released.await(20, TimeUnit.SECONDS);
                return SaveOutcome.of(() -> service.save(first));
            });
            Future<SaveOutcome> secondSave = executor.submit(() -> {
                released.await(20, TimeUnit.SECONDS);
                return SaveOutcome.of(() -> service.save(second));
            });
            return List.of(firstSave.get(30, TimeUnit.SECONDS), secondSave.get(30, TimeUnit.SECONDS));
        }
    }
}
