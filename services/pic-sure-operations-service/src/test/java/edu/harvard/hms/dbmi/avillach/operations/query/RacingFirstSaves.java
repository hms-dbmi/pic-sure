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
import java.util.function.Function;

import org.springframework.transaction.PlatformTransactionManager;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;

/**
 * Runs two first saves so that both look their row up before either inserts it, and both reach the insert of their general query before
 * either makes it. The lookups go through a repository that holds the first two row lookups at a barrier until both threads have made
 * theirs, which is the interleaving two simultaneous submits of a new query produce. The general queries go through a repository that holds
 * the first two {@code insertIfAbsent} calls at a second barrier, before the statement runs, until both threads have arrived. Neither
 * thread has then written a general query, so a save that looked for the general query first and inserted it afterward would have found
 * none in both threads. Later calls pass straight through. Both requests must name a general query, or the second barrier is never
 * released. The two saves store under one id, or each under the id its request carries.
 */
final class RacingFirstSaves {

    private static final int RACERS = 2;
    private static final Set<String> ROW_LOOKUPS = Set.of("findById", "findLockedByUuid");
    private static final String GENERAL_QUERY_INSERT = "insertIfAbsent";

    private RacingFirstSaves() {}

    /**
     * Saves one request from two threads at once, outside any transaction of the caller, and expects both saves to succeed.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param generalQueries the real general query repository
     * @param transactionManager the transaction manager both saves open their own transactions on
     * @param picsureId the id both saves store under
     * @param request the request both saves send
     * @return the id each save returned
     * @throws Exception when a save fails or does not finish in time
     */
    static List<UUID> run(
        QueryRepository queries, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager, UUID picsureId, SaveQueryRequest request
    ) throws Exception {
        List<UUID> saved = new ArrayList<>();
        for (SaveOutcome outcome : run(queries, runs, generalQueries, transactionManager, picsureId, request, request)) {
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
     * @param generalQueries the real general query repository
     * @param transactionManager the transaction manager both saves open their own transactions on
     * @param picsureId the id both saves store under
     * @param first the request one thread sends
     * @param second the request the other thread sends
     * @return how the save of the first request ended, then how the save of the second ended
     * @throws Exception when a save does not finish in time
     */
    static List<SaveOutcome> run(
        QueryRepository queries, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager, UUID picsureId, SaveQueryRequest first, SaveQueryRequest second
    ) throws Exception {
        return race(
            queries, runs, generalQueries, transactionManager, service -> service.save(picsureId, first),
            service -> service.save(picsureId, second)
        );
    }

    /**
     * Saves two requests from two threads at once, each under the id it carries, outside any transaction of the caller. Two requests that
     * name one general query and different ids are two variants of that general query submitted together for the first time.
     *
     * @param queries the real query repository
     * @param runs the real run repository
     * @param generalQueries the real general query repository
     * @param transactionManager the transaction manager both saves open their own transactions on
     * @param first the request one thread sends
     * @param second the request the other thread sends
     * @return how the save of the first request ended, then how the save of the second ended
     * @throws Exception when a save does not finish in time
     */
    static List<SaveOutcome> runUnderTheirOwnIds(
        QueryRepository queries, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager, SaveQueryRequest first, SaveQueryRequest second
    ) throws Exception {
        return race(queries, runs, generalQueries, transactionManager, service -> service.save(first), service -> service.save(second));
    }

    private static List<SaveOutcome> race(
        QueryRepository queries, QueryRunRepository runs, QueryToVariantRepository generalQueries,
        PlatformTransactionManager transactionManager, Function<QueryPersistenceService, UUID> first,
        Function<QueryPersistenceService, UUID> second
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
        CyclicBarrier bothReachedTheGeneralQueryInsert = new CyclicBarrier(RACERS);
        AtomicInteger generalQueryInserts = new AtomicInteger();
        QueryToVariantRepository heldBeforeTheInsert =
            InterceptedRepository.of(QueryToVariantRepository.class, generalQueries, (methodName, call) -> {
                if (GENERAL_QUERY_INSERT.equals(methodName) && generalQueryInserts.incrementAndGet() <= RACERS) {
                    bothReachedTheGeneralQueryInsert.await(20, TimeUnit.SECONDS);
                }
                return call.proceed();
            });
        QueryPersistenceService service = new QueryPersistenceService(heldAtLookup, runs, heldBeforeTheInsert, transactionManager);

        try (var executor = Executors.newFixedThreadPool(RACERS)) {
            Future<SaveOutcome> firstSave = executor.submit(() -> SaveOutcome.of(() -> first.apply(service)));
            Future<SaveOutcome> secondSave = executor.submit(() -> SaveOutcome.of(() -> second.apply(service)));
            return List.of(firstSave.get(30, TimeUnit.SECONDS), secondSave.get(30, TimeUnit.SECONDS));
        }
    }
}
