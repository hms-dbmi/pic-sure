package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import org.springframework.transaction.PlatformTransactionManager;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;

/**
 * Runs three first saves of three variants of one new general query so that the first has inserted the general query and not yet ended when
 * the other two reach their own insert of it, and then fails. The first save is held right after its {@code insertIfAbsent}. The other two
 * then start and wait in the database for the row the first one holds. Once the database reports both of them waiting, the first is let go,
 * its request is refused, and its transaction rolls back, which takes the general query away under the two that waited for it.
 */
final class SavesBehindARolledBackGeneralQuery {

    private static final int WAITERS = 2;
    private static final long POLL_MILLIS = 25;
    private static final long WAIT_FOR_BOTH_MILLIS = 20_000;

    private SavesBehindARolledBackGeneralQuery() {}

    /**
     * Runs the three saves in that interleaving, outside any transaction of the caller.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param generalQueries the real general query repository
     * @param transactionManager the transaction manager every save opens its transactions on
     * @param transactionsWaitingForALock reads how many transactions the database holds in a lock wait
     * @param refused the request of the save that inserts the general query first and then fails; it must be one the service refuses after
     *        that insert
     * @param first the request of one save that waits behind it
     * @param second the request of the other save that waits behind it
     * @return how the refused save ended, then the save of {@code first}, then the save of {@code second}
     * @throws Exception when a save does not finish in time, or the two saves are never both seen waiting
     */
    static List<SaveOutcome> run(
        QueryRepository queries, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager, IntSupplier transactionsWaitingForALock, SaveQueryRequest refused,
        SaveQueryRequest first, SaveQueryRequest second
    ) throws Exception {
        CountDownLatch generalQueryInserted = new CountDownLatch(1);
        CountDownLatch bothOthersWait = new CountDownLatch(1);
        AtomicReference<Thread> failing = new AtomicReference<>();
        QueryToVariantRepository heldAfterTheInsert =
            InterceptedRepository.of(QueryToVariantRepository.class, generalQueries, (methodName, call) -> {
                Object result = call.proceed();
                if ("insertIfAbsent".equals(methodName) && Thread.currentThread() == failing.get()) {
                    generalQueryInserted.countDown();
                    bothOthersWait.await(30, TimeUnit.SECONDS);
                }
                return result;
            });
        QueryPersistenceService service = new QueryPersistenceService(queries, runs, heldAfterTheInsert, transactionManager);

        try (var executor = Executors.newFixedThreadPool(WAITERS + 1)) {
            Future<SaveOutcome> refusedSave = executor.submit(() -> {
                failing.set(Thread.currentThread());
                return SaveOutcome.of(() -> service.save(refused));
            });
            try {
                if (!generalQueryInserted.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the first save never inserted the general query");
                }
                Future<SaveOutcome> firstSave = executor.submit(() -> SaveOutcome.of(() -> service.save(first)));
                Future<SaveOutcome> secondSave = executor.submit(() -> SaveOutcome.of(() -> service.save(second)));
                awaitBothWaiting(transactionsWaitingForALock);
                bothOthersWait.countDown();
                return List
                    .of(refusedSave.get(30, TimeUnit.SECONDS), firstSave.get(30, TimeUnit.SECONDS), secondSave.get(30, TimeUnit.SECONDS));
            } finally {
                bothOthersWait.countDown();
            }
        }
    }

    private static void awaitBothWaiting(IntSupplier transactionsWaitingForALock) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_FOR_BOTH_MILLIS;
        while (transactionsWaitingForALock.getAsInt() < WAITERS) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("the two saves were never both waiting for the general query");
            }
            Thread.sleep(POLL_MILLIS);
        }
    }
}
