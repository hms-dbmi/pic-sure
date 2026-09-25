package edu.harvard.hms.dbmi.avillach.hpds.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.UserConsent;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PartitionedPhenotypicObservationStore;
import edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication;
import edu.harvard.hms.dbmi.avillach.hpds.service.QueryV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;

/**
 * These tests exist because the consents were once held in a {@code @RequestScope} bean, which is reachable only from the thread the
 * servlet container bound the request to. Every async query failed with {@code IllegalStateException: No thread-bound request found},
 * recorded as a bare {@code ERROR} status, and the suite stayed green because the only end-to-end async test replaced that bean with a
 * Mockito mock — a plain singleton, which answers from any thread. Nothing here may substitute the consent source with a mock.
 */
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = HpdsApplication.class, properties = {"hpds.requireAuthorizationFilter=true"})
@ActiveProfiles("integration-test")
class AsyncQueryConsentScopeTest {

    /** A partition directory in the test fixture; a consent grants the partition of the same name. */
    private static final String CONSENT = "partition1";

    private static final String AGE = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";

    @Autowired
    private PartitionedPhenotypicObservationStore partitionedStore;

    @Autowired
    private CountV3Processor countProcessor;

    @Autowired
    private QueryV3Service queryService;

    @BeforeAll
    static void buildDataStore() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    void consentsAreHonouredOnTheCallingThread() {
        assertFalse(partitionedStore.getPatientIds(Set.of(CONSENT)).isEmpty(), "the granted partition should contribute patients");
        assertFalse(countProcessor.runCounts(ageQuery(ResultType.COUNT)) == 0, "a synchronous count should match patients");
    }

    @Test
    void consentsAreHonouredOnAWorkerThread() throws Exception {
        Set<Integer> onCallingThread = partitionedStore.getPatientIds(Set.of(CONSENT));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Set<Integer>> onPoolThread = pool.submit(() -> partitionedStore.getPatientIds(Set.of(CONSENT)));

            assertEquals(onCallingThread, onPoolThread.get(30, TimeUnit.SECONDS), "a worker thread must see the caller's consents");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void asyncQueryCompletesForAConsentedUser() throws Exception {
        AsyncResult result = queryService.runQuery(ageQuery(ResultType.DATAFRAME));

        assertEquals(AsyncResult.Status.SUCCESS, awaitCompletion(result), "an async query should run under the caller's consents");
    }

    private static Query ageQuery(ResultType resultType) {
        return new Query(
            List.of(AGE), List.of(), Set.of(new UserConsent(CONSENT)),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, AGE, null, 35.0, 45.0, null), List.of(), resultType, null, null
        );
    }

    private static AsyncResult.Status awaitCompletion(AsyncResult result) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            AsyncResult.Status status = result.getStatus();
            if (status == AsyncResult.Status.SUCCESS || status == AsyncResult.Status.ERROR) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("async query did not finish within 30s; last status " + result.getStatus());
    }
}
