package edu.harvard.hms.dbmi.avillach.operations.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.PlatformTransactionManager;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;

/**
 * Runs two first saves of one id so that both look the row up before either inserts it. The lookups go through a repository that holds the
 * first two row lookups at a barrier until both threads have made theirs, which is the interleaving two simultaneous submits of a new query
 * produce. Later lookups pass straight through.
 */
final class RacingFirstSaves {

    private static final int RACERS = 2;
    private static final Set<String> ROW_LOOKUPS = Set.of("findById", "findLockedByUuid");

    private RacingFirstSaves() {}

    /**
     * Saves one request from two threads at once, outside any transaction of the caller, and expects both saves to succeed.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param transactionManager the transaction manager both saves open their own transactions on
     * @param picsureId the id both saves store under
     * @param request the request both saves send
     * @return the id each save returned
     * @throws Exception when a save fails or does not finish in time
     */
    static List<UUID> run(
        QueryRepository queries, QueryRunRepository runs, PlatformTransactionManager transactionManager, UUID picsureId,
        SaveQueryRequest request
    ) throws Exception {
        List<UUID> saved = new ArrayList<>();
        for (SaveOutcome outcome : run(queries, runs, transactionManager, picsureId, request, request)) {
            if (outcome.failure() instanceof Exception failure) {
                throw failure;
            }
            saved.add(outcome.saved());
        }
        return saved;
    }

    /**
     * Saves two requests under one id from two threads at once, outside any transaction of the caller.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param transactionManager the transaction manager both saves open their own transactions on
     * @param picsureId the id both saves store under
     * @param first the request one thread sends
     * @param second the request the other thread sends
     * @return how the save of the first request ended, then how the save of the second ended
     * @throws Exception when a save does not finish in time
     */
    static List<SaveOutcome> run(
        QueryRepository queries, QueryRunRepository runs, PlatformTransactionManager transactionManager, UUID picsureId,
        SaveQueryRequest first, SaveQueryRequest second
    ) throws Exception {
        CyclicBarrier bothLookedUp = new CyclicBarrier(RACERS);
        AtomicInteger lookups = new AtomicInteger();
        QueryRepository heldAtLookup = InterceptedRepository.of(QueryRepository.class, queries, (methodName, call) -> {
            Object result = call.proceed();
            if (ROW_LOOKUPS.contains(methodName) && lookups.incrementAndGet() <= RACERS) {
                bothLookedUp.await(20, TimeUnit.SECONDS);
            }
            return result;
        });
        QueryPersistenceService service = new QueryPersistenceService(heldAtLookup, runs, transactionManager);

        try (var executor = Executors.newFixedThreadPool(RACERS)) {
            Future<SaveOutcome> firstSave = executor.submit(() -> SaveOutcome.of(() -> service.save(picsureId, first)));
            Future<SaveOutcome> secondSave = executor.submit(() -> SaveOutcome.of(() -> service.save(picsureId, second)));
            return List.of(firstSave.get(30, TimeUnit.SECONDS), secondSave.get(30, TimeUnit.SECONDS));
        }
    }
}
