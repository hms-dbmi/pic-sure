package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.util.UUIDv5;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary.DictionaryService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.io.CsvWriter;
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
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryV3ServiceResultMapTest {

    private static final int SMALL_JOB_LIMIT = 100;
    private static final long WAIT_SECONDS = 30;

    private final CountDownLatch workerStarted = new CountDownLatch(1);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    private MultiValueQueryV3Processor multiValueProcessor;
    private DictionaryService dictionaryService;
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
        dictionaryService = mock(DictionaryService.class);
        service = new QueryV3Service(
            mock(TimeseriesV3Processor.class), mock(CountV3Processor.class), multiValueProcessor, dictionaryService, queryValidator,
            SMALL_JOB_LIMIT, 1, 1, mock(PatientV3Processor.class), mock(LoggingClient.class)
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
        doThrow(new IllegalStateException("processing failed")).doNothing().when(multiValueProcessor).runQuery(any(), any());
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        awaitStatus(first, AsyncResult.Status.ERROR);

        AsyncResult second = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertNotSame(first, second);
        assertSame(second, service.getResultFor(picsureId));
        finishAllJobs();
        verify(multiValueProcessor, times(2)).runQuery(any(), any());
    }

    @Test
    void submitForAJobInRetryReturnsTheSameJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        first.setStatus(AsyncResult.Status.RETRY);

        AsyncResult second = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertSame(first, second);
        assertSame(first, service.getResultFor(picsureId));
        assertEquals(0, smallQueue().size());
    }

    @Test
    void validSubmitRunsAfterASubmitThatFailedValidationWithAnUnexpectedException() throws Exception {
        releaseWorker.countDown();
        UUID picsureId = UUID.randomUUID();
        doThrow(new NullPointerException("filter has no type")).doNothing().when(queryValidator).validate(any());
        assertThrows(NullPointerException.class, () -> service.runQuery(query(picsureId, consents("phs1.c1"))));

        AsyncResult second = service.runQuery(query(picsureId, consents("phs1.c1")));

        awaitStatus(second, AsyncResult.Status.SUCCESS);
        assertSame(second, service.getResultFor(picsureId));
        verify(multiValueProcessor, times(1)).runQuery(any(), any());
    }

    @Test
    void submitThatFailsValidationWithAnUnexpectedExceptionStoresNothing() {
        UUID picsureId = UUID.randomUUID();
        doThrow(new NullPointerException("filter has no type")).when(queryValidator).validate(any());

        assertThrows(NullPointerException.class, () -> service.runQuery(query(picsureId, consents("phs1.c1"))));

        assertNull(service.getResultFor(picsureId));
        assertNull(service.getStatusFor(picsureId.toString()));
    }

    @Test
    void invalidSubmitForAnUnusedIdReturnsAnErrorAndStoresNothing() throws Exception {
        UUID picsureId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("invalid")).when(queryValidator).validate(any());

        AsyncResult rejected = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertEquals(AsyncResult.Status.ERROR, rejected.getStatus());
        assertEquals(picsureId.toString(), rejected.getId());
        assertNull(service.getResultFor(picsureId));
        assertFalse(Files.exists(rejected.getTempFilePath()));
        assertEquals(0, smallQueue().size());
    }

    @Test
    void invalidResubmitKeepsTheFinishedResult() throws Exception {
        releaseWorker.countDown();
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        awaitStatus(first, AsyncResult.Status.SUCCESS);
        doThrow(new IllegalArgumentException("invalid")).when(queryValidator).validate(any());

        AsyncResult rejected = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertEquals(AsyncResult.Status.ERROR, rejected.getStatus());
        assertSame(first, service.getResultFor(picsureId));
        assertEquals(AsyncResult.Status.SUCCESS, first.getStatus());
        assertTrue(Files.exists(first.getTempFilePath()));
    }

    @Test
    void invalidResubmitKeepsTheRunningJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        doThrow(new IllegalArgumentException("invalid")).when(queryValidator).validate(any());

        AsyncResult rejected = service.runQuery(query(picsureId, consents("phs2.c1")));

        assertEquals(AsyncResult.Status.ERROR, rejected.getStatus());
        assertSame(first, service.getResultFor(picsureId));
        assertEquals(AsyncResult.Status.RUNNING, first.getStatus());
        assertEquals(0, smallQueue().size());
    }

    @Test
    void invalidResubmitOfTheQueryOfARunningJobIsRejectedAndNotGivenThatJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AsyncResult first = service.runQuery(query(picsureId, consents("phs1.c1")));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        doThrow(new IllegalArgumentException("invalid")).when(queryValidator).validate(any());

        AsyncResult rejected = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertNotSame(first, rejected);
        assertEquals(AsyncResult.Status.ERROR, rejected.getStatus());
        assertSame(first, service.getResultFor(picsureId));
        assertEquals(AsyncResult.Status.RUNNING, first.getStatus());
    }

    @Test
    void invalidSubmitClosesTheWriterOfTheJobItDiscards() throws Exception {
        doThrow(new IllegalArgumentException("invalid")).when(queryValidator).validate(any());
        try (MockedConstruction<CsvWriter> writers = mockConstruction(CsvWriter.class, QueryV3ServiceResultMapTest::writesToItsFile)) {
            AsyncResult rejected = service.runQuery(query(UUID.randomUUID(), consents("phs1.c1")));

            assertEquals(AsyncResult.Status.ERROR, rejected.getStatus());
            assertEquals(1, writers.constructed().size());
            verify(writers.constructed().get(0)).close();
            assertFalse(Files.exists(rejected.getTempFilePath()));
        }
    }

    @Test
    void submitWhoseJobCannotBeBuiltClosesTheWriterAndDeletesTheTemporaryFile() {
        UUID picsureId = UUID.randomUUID();
        when(multiValueProcessor.getHeaderRow(any())).thenThrow(new IllegalStateException("no header"));
        try (MockedConstruction<CsvWriter> writers = mockConstruction(CsvWriter.class, QueryV3ServiceResultMapTest::writesToItsFile)) {
            assertThrows(IllegalStateException.class, () -> service.runQuery(query(picsureId, consents("phs1.c1"))));

            assertEquals(1, writers.constructed().size());
            CsvWriter writer = writers.constructed().get(0);
            verify(writer).close();
            assertTrue(writer.getFile().getName().startsWith("result-"));
            assertFalse(writer.getFile().exists());
        }
        assertNull(service.getResultFor(picsureId));
    }

    @Test
    void submitWhoseJobCannotBeBuiltThrowsTheBuildFailureWhenTheWriterAlsoFailsToClose() {
        IllegalStateException buildFailure = new IllegalStateException("no header");
        when(multiValueProcessor.getHeaderRow(any())).thenThrow(buildFailure);
        try (MockedConstruction<CsvWriter> writers = mockConstruction(CsvWriter.class, (writer, context) -> {
            writesToItsFile(writer, context);
            doThrow(new UncheckedIOException(new IOException("close failed"))).when(writer).close();
        })) {
            IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> service.runQuery(query(UUID.randomUUID(), consents("phs1.c1"))));

            assertSame(buildFailure, thrown);
            assertFalse(writers.constructed().get(0).getFile().exists());
        }
    }

    @Test
    void submitWhoseEnqueueFailsLeavesTheStoredJobInError() {
        UUID picsureId = UUID.randomUUID();
        ExecutorService realExecutor = smallExecutor();
        ExecutorService failingExecutor = mock(ExecutorService.class);
        doThrow(new IllegalStateException("queue failed")).when(failingExecutor).execute(any());
        ReflectionTestUtils.setField(service, "smallTaskExecutor", failingExecutor);
        try {
            assertThrows(IllegalStateException.class, () -> service.runQuery(query(picsureId, consents("phs1.c1"))));
        } finally {
            ReflectionTestUtils.setField(service, "smallTaskExecutor", realExecutor);
        }

        AsyncResult stored = service.getResultFor(picsureId);
        assertEquals(AsyncResult.Status.ERROR, stored.getStatus());
        assertEquals(0, smallQueue().size());
    }

    @Test
    void submitThatTheExecutorRejectsLeavesAnErroredJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        smallExecutor().shutdown();

        AsyncResult result = service.runQuery(query(picsureId, consents("phs1.c1")));

        assertEquals(AsyncResult.Status.ERROR, result.getStatus());
        assertSame(result, service.getResultFor(picsureId));
    }

    @Test
    void slowJobConstructionDoesNotBlockAnotherSubmitOfTheSameId() throws Exception {
        UUID picsureId = UUID.randomUUID();
        Set<String> avroFilesBefore = avroTempFiles();
        CountDownLatch dictionaryCalled = new CountDownLatch(1);
        CountDownLatch releaseDictionary = new CountDownLatch(1);
        when(dictionaryService.getConcepts(any())).thenAnswer(invocation -> {
            dictionaryCalled.countDown();
            releaseDictionary.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return List.of();
        }).thenReturn(List.of());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<AsyncResult> slow = callers.submit(() -> service.runQuery(pfbQuery(picsureId)));
            assertTrue(dictionaryCalled.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<AsyncResult> fast = callers.submit(() -> service.runQuery(pfbQuery(picsureId)));
            AsyncResult stored = fast.get(5, TimeUnit.SECONDS);
            assertFalse(slow.isDone());
            releaseDictionary.countDown();

            assertSame(stored, slow.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertSame(stored, service.getResultFor(picsureId));
            Set<String> avroFilesCreated = avroTempFiles();
            avroFilesCreated.removeAll(avroFilesBefore);
            assertEquals(Set.of(stored.getTempFilePath().getFileName().toString()), avroFilesCreated);
        } finally {
            releaseDictionary.countDown();
            callers.shutdownNow();
        }
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
    void submitForARunningIdWithADifferentSelectReplacesIt() throws Exception {
        Query changed = new Query(List.of("\\demographics\\SEX\\"), consents("phs1.c1"), null, List.of(), ResultType.DATAFRAME, null, null);

        assertARunningJobIsReplacedBy(changed);
    }

    @Test
    void submitForARunningIdWithTheSameSelectInAnotherOrderReplacesIt() throws Exception {
        List<String> select = List.of("\\demographics\\AGE\\", "\\demographics\\SEX\\");
        List<String> reordered = List.of("\\demographics\\SEX\\", "\\demographics\\AGE\\");
        Query running = new Query(select, consents("phs1.c1"), null, List.of(), ResultType.DATAFRAME, null, null);
        Query changed = new Query(reordered, consents("phs1.c1"), null, List.of(), ResultType.DATAFRAME, null, null);

        assertARunningJobIsReplacedBy(running, changed);
    }

    @Test
    void submitForARunningIdWithADifferentExpectedResultTypeReplacesIt() throws Exception {
        Query changed =
            new Query(List.of("\\demographics\\AGE\\"), consents("phs1.c1"), null, List.of(), ResultType.DATAFRAME_PFB, null, null);

        assertARunningJobIsReplacedBy(changed);
    }

    @Test
    void submitForARunningIdWithADifferentSampleFlagReplacesIt() throws Exception {
        Query changed =
            new Query(List.of("\\demographics\\AGE\\"), consents("phs1.c1"), null, List.of(), ResultType.DATAFRAME, null, null, true);

        assertARunningJobIsReplacedBy(changed);
    }

    @Test
    void submitForARunningIdWithADifferentPhenotypicClauseReplacesIt() throws Exception {
        PhenotypicFilter females =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\SEX\\", Set.of("Female"), null, null, null);
        Query changed =
            new Query(List.of("\\demographics\\AGE\\"), consents("phs1.c1"), females, List.of(), ResultType.DATAFRAME, null, null);

        assertARunningJobIsReplacedBy(changed);
    }

    @Test
    void submitForARunningIdWithDifferentGenomicFiltersReplacesIt() throws Exception {
        List<GenomicFilter> apoe = List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null));
        Query changed = new Query(List.of("\\demographics\\AGE\\"), consents("phs1.c1"), null, apoe, ResultType.DATAFRAME, null, null);

        assertARunningJobIsReplacedBy(changed);
    }

    @Test
    void submitForARunningIdWithTheSameQueryUnderAnotherQueryIdReturnsTheSameJob() throws Exception {
        UUID picsureId = UUID.randomUUID();
        PhenotypicFilter females =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\SEX\\", Set.of("Female"), null, null, null);
        List<GenomicFilter> apoe = List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null));
        List<String> select = List.of("\\demographics\\AGE\\", "\\demographics\\SEX\\");
        Query running =
            new Query(select, consents("phs1.c1", "phs2.c1"), females, apoe, ResultType.DATAFRAME, picsureId, UUID.randomUUID(), true);
        Query resubmitted = new Query(
            new ArrayList<>(select), consents("phs2.c1", "phs1.c1"),
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\demographics\\SEX\\", Set.of("Female"), null, null, null),
            List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null)), ResultType.DATAFRAME, picsureId,
            UUID.randomUUID(), true
        );
        AsyncResult first = service.runQuery(running);
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));

        AsyncResult second = service.runQuery(resubmitted);

        assertSame(first, second);
        assertSame(first, service.getResultFor(picsureId));
        assertEquals(0, smallQueue().size());
        finishAllJobs();
        verify(multiValueProcessor, times(1)).runQuery(any(), any());
    }

    @Test
    void everyComponentOfAQueryIsEitherComparedForReuseOrKnownToBeIgnored() {
        Set<String> compared =
            Set.of("select", "authorizationFilters", "phenotypicClause", "genomicFilters", "expectedResultType", "sample");
        Set<String> ignored = Set.of("picsureId", "id");
        Set<String> components = new HashSet<>();
        for (RecordComponent component : Query.class.getRecordComponents()) {
            components.add(component.getName());
        }
        Set<String> expected = new HashSet<>(compared);
        expected.addAll(ignored);

        assertEquals(expected, components, "a new Query component must be added to QueryV3Service.isSameQuery or to the ignored set here");
    }

    private void assertARunningJobIsReplacedBy(Query changed) throws Exception {
        assertARunningJobIsReplacedBy(query(null, consents("phs1.c1")), changed);
    }

    /**
     * Submits {@code running}, waits until its job runs, then submits {@code changed} under the same result id. Asserts that the second
     * submit gets a new job built from {@code changed}, that the job takes over the id and is enqueued, and that the processor's next run
     * receives {@code changed} and that job.
     */
    private void assertARunningJobIsReplacedBy(Query running, Query changed) throws Exception {
        UUID picsureId = UUID.randomUUID();
        Query submitted = changed.setPicsureId(picsureId);
        AsyncResult first = service.runQuery(running.setPicsureId(picsureId));
        assertTrue(workerStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(AsyncResult.Status.RUNNING, first.getStatus());

        AsyncResult second = service.runQuery(submitted);

        assertNotSame(first, second);
        assertEquals(picsureId.toString(), second.getId());
        assertEquals(submitted, withoutQueryId(second.getQuery()));
        assertSame(second, service.getResultFor(picsureId));
        assertSame(second, service.getStatusFor(picsureId.toString()));
        assertEquals(1, smallQueue().size());
        finishAllJobs();
        ArgumentCaptor<Query> executed = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<AsyncResult> jobs = ArgumentCaptor.forClass(AsyncResult.class);
        verify(multiValueProcessor, times(2)).runQuery(executed.capture(), jobs.capture());
        assertSame(second, jobs.getAllValues().get(1));
        assertEquals(submitted, withoutQueryId(executed.getAllValues().get(1)));
        assertEquals(AsyncResult.Status.SUCCESS, second.getStatus());
        assertSame(second, service.getResultFor(picsureId));
    }

    /** Returns the query without the random id a job gives the query it runs, so it can be compared with the query as submitted. */
    private static Query withoutQueryId(Query query) {
        return new Query(
            query.select(), query.authorizationFilters(), query.phenotypicClause(), query.genomicFilters(), query.expectedResultType(),
            query.picsureId(), null, query.sample()
        );
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

    /** Makes a constructed writer report the temporary file it was constructed with, as the real writer does. */
    private static void writesToItsFile(CsvWriter writer, MockedConstruction.Context context) {
        when(writer.getFile()).thenReturn((File) context.arguments().get(0));
    }

    private static Set<String> avroTempFiles() {
        String[] names =
            new File(System.getProperty("java.io.tmpdir")).list((dir, name) -> name.startsWith("result-") && name.endsWith(".avro"));
        return new HashSet<>(Arrays.asList(names == null ? new String[0] : names));
    }

    private static Query pfbQuery(UUID picsureId) {
        return new Query(List.of("\\demographics\\AGE\\"), consents("phs1.c1"), null, List.of(), ResultType.DATAFRAME_PFB, picsureId, null);
    }

    private static List<AuthorizationFilter> consents(String... values) {
        return List.of(new AuthorizationFilter(AuthorizationFilter.CONSENTS_CONCEPT_PATH, Set.of(values)));
    }

    private static Query query(UUID picsureId, List<AuthorizationFilter> authorizationFilters) {
        return new Query(List.of("\\demographics\\AGE\\"), authorizationFilters, null, List.of(), ResultType.DATAFRAME, picsureId, null);
    }
}
