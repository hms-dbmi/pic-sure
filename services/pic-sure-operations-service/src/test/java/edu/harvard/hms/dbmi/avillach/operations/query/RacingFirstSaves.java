package edu.harvard.hms.dbmi.avillach.operations.query;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
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
 * first two {@code findById} calls at a barrier until both threads have made theirs, which is the interleaving two simultaneous submits of
 * a new query produce. Later lookups pass straight through.
 */
final class RacingFirstSaves {

    private static final int RACERS = 2;

    private RacingFirstSaves() {}

    /**
     * Saves the request from two threads at once, outside any transaction of the caller.
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
        CyclicBarrier bothLookedUp = new CyclicBarrier(RACERS);
        AtomicInteger lookups = new AtomicInteger();
        QueryRepository heldAtLookup = (QueryRepository) Proxy
            .newProxyInstance(QueryRepository.class.getClassLoader(), new Class<?>[] {QueryRepository.class}, (proxy, method, args) -> {
                Object result;
                try {
                    result = method.invoke(queries, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (method.getName().equals("findById") && lookups.incrementAndGet() <= RACERS) {
                    bothLookedUp.await(20, TimeUnit.SECONDS);
                }
                return result;
            });
        QueryPersistenceService service = new QueryPersistenceService(heldAtLookup, runs, transactionManager);

        try (var executor = Executors.newFixedThreadPool(RACERS)) {
            Future<UUID> first = executor.submit(() -> service.save(picsureId, request));
            Future<UUID> second = executor.submit(() -> service.save(picsureId, request));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }
}
