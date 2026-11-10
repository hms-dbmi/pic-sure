package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;

/**
 * Runs an update and a save of one row so that the update has read the row and its latest run before the save starts, and writes only after
 * the save has had time to commit. That is a poller reporting the status of the run it read while the query is submitted again.
 *
 * <p>The update is held right after it reads the latest run. The save then gets {@link #SAVE_HEAD_START_MILLIS} to finish. A save that
 * waits on the row the update holds does not finish in that time, and it completes after the update is let go. The update runs in a
 * transaction opened here, because the service built here is not the container's and carries no transaction of its own on that method.
 */
final class UpdateHeldAcrossASave {

    private static final long SAVE_HEAD_START_MILLIS = 400;

    private UpdateHeldAcrossASave() {}

    /**
     * Runs the update and the save in that interleaving, outside any transaction of the caller.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param transactionManager the transaction manager the update and the save open their transactions on
     * @param picsureId the id of the row both write
     * @param update the update that reads first and writes last
     * @param save the save that starts after the update has read
     * @return true when the save committed while the update was held, false when it waited for the update to finish
     * @throws Exception when the update or the save fails or does not finish in time
     */
    static boolean run(
        QueryRepository queries, QueryRunRepository runs, PlatformTransactionManager transactionManager, UUID picsureId,
        UpdateQueryRequest update, SaveQueryRequest save
    ) throws Exception {
        CountDownLatch updateReadItsRun = new CountDownLatch(1);
        CountDownLatch saveHadItsHeadStart = new CountDownLatch(1);
        AtomicReference<Thread> updater = new AtomicReference<>();
        QueryRunRepository heldAfterLatestRunRead = InterceptedRepository.of(QueryRunRepository.class, runs, (methodName, call) -> {
            Object result = call.proceed();
            boolean updaterReadLatestRun =
                methodName.equals("findFirstByQueryUuidOrderBySubmittedAtDesc") && Thread.currentThread() == updater.get();
            if (updaterReadLatestRun && updateReadItsRun.getCount() > 0) {
                updateReadItsRun.countDown();
                saveHadItsHeadStart.await(30, TimeUnit.SECONDS);
            }
            return result;
        });
        QueryPersistenceService updating = new QueryPersistenceService(queries, heldAfterLatestRunRead, transactionManager);
        QueryPersistenceService saving = new QueryPersistenceService(queries, runs, transactionManager);
        TransactionTemplate updateTransaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> updated = executor.submit(() -> {
                updater.set(Thread.currentThread());
                updateTransaction.executeWithoutResult(status -> updating.update(picsureId, update));
            });
            if (!updateReadItsRun.await(30, TimeUnit.SECONDS)) {
                saveHadItsHeadStart.countDown();
                updated.get(30, TimeUnit.SECONDS);
                throw new IllegalStateException("the update never read the latest run");
            }
            Future<UUID> saved = executor.submit(() -> saving.save(save));
            boolean savedWhileTheUpdateWasHeld = finishedWithinTheHeadStart(saved);
            saveHadItsHeadStart.countDown();
            updated.get(30, TimeUnit.SECONDS);
            saved.get(30, TimeUnit.SECONDS);
            return savedWhileTheUpdateWasHeld;
        }
    }

    private static boolean finishedWithinTheHeadStart(Future<UUID> saved) throws Exception {
        try {
            saved.get(SAVE_HEAD_START_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException waitingOnTheRow) {
            return false;
        }
    }
}
