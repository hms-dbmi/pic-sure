package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.MultiValueQueryV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PatientV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryValidator;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.TimeseriesV3Processor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryV3ServiceResultMapTest {

    private static final int SMALL_JOB_LIMIT = 100;
    private static final long WAIT_SECONDS = 30;

    private final CountDownLatch workerStarted = new CountDownLatch(1);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    private MultiValueQueryV3Processor multiValueProcessor;
    private QueryValidator queryValidator;
    private QueryV3Service service;

    @BeforeEach
    void setup() {
        multiValueProcessor = mock(MultiValueQueryV3Processor.class);
        when(multiValueProcessor.getHeaderRow(any())).thenReturn(new String[] {"patient_id"});
        doAnswer(invocation -> {
            workerStarted.countDown();
            releaseWorker.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }).when(multiValueProcessor).runQuery(any(), any());
        queryValidator = mock(QueryValidator.class);
        service = new QueryV3Service(
            mock(TimeseriesV3Processor.class), mock(CountV3Processor.class), multiValueProcessor, null, queryValidator, SMALL_JOB_LIMIT, 1,
            1, mock(PatientV3Processor.class), mock(LoggingClient.class)
        );
    }

    @AfterEach
    void shutdown() {
        releaseWorker.countDown();
        smallExecutor().shutdownNow();
        ((ExecutorService) ReflectionTestUtils.getField(service, "largeTaskExecutor")).shutdownNow();
    }

    @Test
    void resultIsKeyedByPicsureIdWhenPresent() throws IOException {
        UUID picsureId = UUID.randomUUID();

        AsyncResult result = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertEquals(picsureId.toString(), result.getId());
        assertSame(result, service.getResultFor(picsureId));
        assertEquals(picsureId, QueryV3Service.resultKeyFor(query(picsureId, consents("phs1.c1"))));
    }

    @Test
    void resultIsKeyedByContentHashWhenPicsureIdIsAbsent() throws IOException {
        Query query = query(null, consents("phs1.c1"));
        UUID contentHash = UUIDv5.UUIDFromString(query.toString());

        AsyncResult result = service.runQuery(query);

        assertEquals(contentHash.toString(), result.getId());
        assertSame(result, service.getResultFor(contentHash));
        assertEquals(contentHash, QueryV3Service.resultKeyFor(query));
    }

    @Test
    void submitForARunningIdReturnsTheSameJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1", "phs2.c1")));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(AsyncResult.Status.RUNNING, first.getStatus());

        AsyncResult second = service.runQuery(query(picsureId, consents("phs2.c1", "phs1.c1")));

        assertSame(first, second);
        assertSame(first, service.getResultFor(picsureId));
        assertEquals(0, smallQueue().size());
        finishAllJobs();
        verify(multiValueProcessor, times(1)).runQuery(any(), any());
    }

    @Test
    void submitForAQueuedIdReturnsTheSameJob() throws Exception {
        service.runQuery(query(UUID.randomUUID(), consents("phs1.c1")));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        assertEquals(AsyncResult.Status.PENDING, first.getStatus());

        AsyncResult second = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertSame(first, second);
        assertEquals(1, smallQueue().size());
        finishAllJobs();
        verify(multiValueProcessor, times(2)).runQuery(any(), any());
    }

    @Test
    void submitForARunningIdWithTheSameFiltersInAnotherOrderReturnsTheSameJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AuthorizationFilter consents = new AuthorizationFilter(AuthorizationFilter.CONSENTS_CONCEPT_PATH, Set.of("phs1.c1"));
        AuthorizationFilter topmed = new AuthorizationFilter("\\_topmed_consents\\", Set.of("phs1.c1"));
        AsyncResult first = service.runQuery(query(picsureId, List.of(consents, topmed)));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));

        AsyncResult second = service.runQuery(query(picsureId, List.of(topmed, consents)));

        assertSame(first, second);
        assertEquals(0, smallQueue().size());
    }

    @Test
    void submitForACompletedIdReplacesIt() throws Exception {
        releaseWorker.countDown();
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        awaitStatus(first, AsyncResult.Status.SUCCESS);

        AsyncResult second = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertNotSame(first, second);
        assertSame(second, service.getResultFor(picsureId));
        finishAllJobs();
        verify(multiValueProcessor, times(2)).runQuery(any(), any());
    }

    @Test
    void submitForAnErroredIdReplacesIt() throws Exception {
        UUID picsureId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("invalid")).doNothing().when(queryValidator).validate(any());
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        assertEquals(AsyncResult.Status.ERROR, first.getStatus());

        AsyncResult second = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertNotSame(first, second);
        assertSame(second, service.getResultFor(picsureId));
        finishAllJobs();
        verify(multiValueProcessor, times(1)).runQuery(any(), any());
    }

    @Test
    void submitForARunningIdWithDifferentAuthorizationFiltersReplacesIt() throws Exception {
        UUID picsureId = UUID.randomUUID();
        List<AuthorizationFilter> oldConsents = consents("phs1.c1", "phs2.c1");
        List<AuthorizationFilter> newConsents = consents("phs1.c1");
        AsyncResult first = service.runQuery(query(picsureId, oldConsents));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(AsyncResult.Status.RUNNING, first.getStatus());

        AsyncResult second = service.runQuery(query(picsureId, newConsents));

        assertNotSame(first, second);
        assertEquals(picsureId.toString(), second.getId());
        assertEquals(newConsents, second.getQuery().authorizationFilters());
        assertSame(second, service.getResultFor(picsureId));
        assertSame(second, service.getStatusFor(picsureId.toString()));
        assertEquals(1, smallQueue().size());
        finishAllJobs();
        ArgumentCaptor<Query> executed = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<AsyncResult> jobs = ArgumentCaptor.forClass(AsyncResult.class);
        verify(multiValueProcessor, times(2)).runQuery(executed.capture(), jobs.capture());
        assertSame(second, jobs.getAllValues().get(1));
        assertEquals(newConsents, executed.getAllValues().get(1).authorizationFilters());
        assertEquals(AsyncResult.Status.SUCCESS, second.getStatus());
        assertSame(second, service.getResultFor(picsureId));
    }

    @Test
    void submitForARunningIdWithNoAuthorizationFiltersReplacesAJobThatHadThem() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));

        AsyncResult second = service.runQuery(query(picsureId, null));

        assertNotSame(first, second);
        assertEquals(List.of(), second.getQuery().authorizationFilters());
        assertSame(second, service.getResultFor(picsureId));
    }

    @Test
    void twoConcurrentSubmitsOfOneIdEnqueueOneJob() throws Exception {
        int rounds = 25;
        int submitters = 8;
        ExecutorService callers = Executors.newFixedThreadPool(submitters);
        try {
            for (int round = 0; round < rounds; round++) {
                UUID picsureId = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<AsyncResult>> submitted = new ArrayList<>();
                for (int submitter = 0; submitter < submitters; submitter++) {
                    Callable<AsyncResult> submit = () -> {
                        start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                        return service.runQuery(query(picsureId, consents("phs1.c1")));
                    };
                    submitted.add(callers.submit(submit));
                }
                start.countDown();
                AsyncResult stored = service.getResultFor(picsureId);
                for (Future<AsyncResult> future : submitted) {
                    AsyncResult returned = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    stored = stored == null ? returned : stored;
                    assertSame(stored, returned);
                }
                assertSame(stored, service.getResultFor(picsureId));
            }
        } finally {
            callers.shutdownNow();
        }
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(rounds - 1, smallQueue().size());
        finishAllJobs();
        verify(multiValueProcessor, times(rounds)).runQuery(any(), any());
    }

    @Test
    void statusForAnUnknownIdIsNull() {
        assertNull(service.getStatusFor(UUID.randomUUID().toString()));
    }

    private void finishAllJobs() throws InterruptedException {
        releaseWorker.countDown();
        smallExecutor().shutdown();
        assertTrue(smallExecutor().awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private static void awaitStatus(AsyncResult result, AsyncResult.Status expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (result.getStatus() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, result.getStatus());
    }

    private ExecutorService smallExecutor() {
        return (ExecutorService) ReflectionTestUtils.getField(service, "smallTaskExecutor");
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Runnable> smallQueue() {
        return (BlockingQueue<Runnable>) ReflectionTestUtils.getField(service, "smallTaskExecutionQueue");
    }

    private static List<AuthorizationFilter> consents(String... values) {
        return List.of(new AuthorizationFilter(AuthorizationFilter.CONSENTS_CONCEPT_PATH, Set.of(values)));
    }

    private static Query query(UUID picsureId, List<AuthorizationFilter> authorizationFilters) {
        return new Query(List.of("\\demographics\\AGE\\"), authorizationFilters, null, List.of(), ResultType.DATAFRAME, picsureId, null);
    }
}
