package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.MultiValueQueryV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.PatientV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryValidator;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.TimeseriesV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSystemV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PicSureV3ServiceWriteTest {

    private static final long WAIT_SECONDS = 30;
    private static final int SMALL_JOB_LIMIT = 100;

    private final CountDownLatch largeJobStarted = new CountDownLatch(1);
    private final CountDownLatch releaseLargeJob = new CountDownLatch(1);

    @TempDir
    Path sharingRoot;

    private QueryV3Service queryService;
    private PicSureV3Service service;

    @BeforeEach
    void setup() {
        TimeseriesV3Processor timeseriesProcessor = mock(TimeseriesV3Processor.class);
        when(timeseriesProcessor.getHeaderRow(any())).thenReturn(new String[] {"consent"});
        doAnswer(writesTheConsentsOfTheQuery()).when(timeseriesProcessor).runQuery(any(), any());
        PatientV3Processor patientProcessor = mock(PatientV3Processor.class);
        when(patientProcessor.getHeaderRow(any())).thenReturn(new String[] {"consent"});
        doAnswer(writesTheConsentsOfTheQuery()).when(patientProcessor).runQuery(any(), any());
        queryService = spy(
            new QueryV3Service(
                timeseriesProcessor, mock(CountV3Processor.class), mock(MultiValueQueryV3Processor.class), null, mock(QueryValidator.class),
                SMALL_JOB_LIMIT, 1, 1, patientProcessor, mock(LoggingClient.class)
            )
        );
        FileSystemV3Service fileSystem = new FileSystemV3Service();
        ReflectionTestUtils.setField(fileSystem, "sharingRoot", sharingRoot);
        ReflectionTestUtils.setField(fileSystem, "enableFileSharing", true);
        FileSharingV3Service fileSharing =
            new FileSharingV3Service(fileSystem, mock(VariantListV3Processor.class), mock(LoggingClient.class));
        service = new PicSureV3Service(
            queryService, mock(CountV3Processor.class), mock(VariantListV3Processor.class), mock(QueryExecutor.class),
            mock(Paginator.class), mock(SignUrlService.class), fileSharing, mock(TestDataService.class)
        );
        ReflectionTestUtils.setField(service, "httpRequest", new MockHttpServletRequest());
    }

    @AfterEach
    void shutdown() {
        releaseLargeJob.countDown();
        ((ExecutorService) ReflectionTestUtils.getField(queryService, "smallTaskExecutor")).shutdownNow();
        ((ExecutorService) ReflectionTestUtils.getField(queryService, "largeTaskExecutor")).shutdownNow();
    }

    @Test
    void phenotypicWriteCopiesTheFileOfTheJobItSubmittedWhenOtherSubmitsTakeOverItsKeys() throws Exception {
        assertWriteCopiesTheFileOfTheJobItSubmitted("phenotypic", ResultType.DATAFRAME_TIMESERIES, "phenotypic_data.csv");
    }

    @Test
    void patientsWriteCopiesTheFileOfTheJobItSubmittedWhenOtherSubmitsTakeOverItsKeys() throws Exception {
        assertWriteCopiesTheFileOfTheJobItSubmitted("patients", ResultType.PATIENTS, "patients.txt");
    }

    @Test
    void writeNamingTheIdOfAFinishedJobLeavesThatJobStored() throws Exception {
        UUID existingId = UUID.randomUUID();
        AsyncResult existing = queryService.runQuery(query(ResultType.DATAFRAME_TIMESERIES, existingId, "phs1.c1"));
        awaitSuccess(existing);
        String existingContent = Files.readString(existing.getTempFilePath());
        AsyncResult[] written = new AsyncResult[1];
        doAnswer(invocation -> {
            written[0] = (AsyncResult) invocation.callRealMethod();
            awaitSuccess(written[0]);
            return written[0];
        }).when(queryService).runQuery(any(), any());

        ResponseEntity<String> response =
            service.writeQueryResult(query(ResultType.DATAFRAME_TIMESERIES, existingId, "phs2.c1"), "phenotypic");

        assertEquals(200, response.getStatusCode().value());
        assertSame(existing, queryService.getResultFor(existingId));
        assertEquals(AsyncResult.Status.SUCCESS, existing.getStatus());
        assertEquals(existingContent, Files.readString(existing.getTempFilePath()));
        assertNotSame(existing, written[0]);
        assertNotEquals(existingId.toString(), written[0].getId());
        String file = Files.readString(sharingRoot.resolve(existingId.toString()).resolve("phenotypic_data.csv"));
        assertTrue(file.contains("phs2.c1"), file);
        assertFalse(file.contains("phs1.c1"), file);
    }

    @Test
    void writeNamingTheContentKeyOfARunningJobLeavesThatJobStored() throws Exception {
        Query running = largeQuery("phs1.c1");
        UUID runningKey = QueryV3Service.resultKeyFor(running);
        AsyncResult existing = queryService.runQuery(running);
        assertTrue(largeJobStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(AsyncResult.Status.RUNNING, existing.getStatus());
        AsyncResult[] written = new AsyncResult[1];
        doAnswer(invocation -> {
            written[0] = (AsyncResult) invocation.callRealMethod();
            awaitSuccess(written[0]);
            return written[0];
        }).when(queryService).runQuery(any(), any());

        ResponseEntity<String> response =
            service.writeQueryResult(query(ResultType.DATAFRAME_TIMESERIES, runningKey, "phs2.c1"), "phenotypic");

        assertEquals(200, response.getStatusCode().value());
        assertSame(existing, queryService.getResultFor(runningKey));
        assertEquals(AsyncResult.Status.RUNNING, existing.getStatus());
        assertNotSame(existing, written[0]);
        assertNotEquals(runningKey.toString(), written[0].getId());
        String file = Files.readString(sharingRoot.resolve(runningKey.toString()).resolve("phenotypic_data.csv"));
        assertTrue(file.contains("phs2.c1"), file);
        assertFalse(file.contains("phs1.c1"), file);
        releaseLargeJob.countDown();
        awaitSuccess(existing);
        assertSame(existing, queryService.getResultFor(runningKey));
        assertTrue(Files.readString(existing.getTempFilePath()).contains("phs1.c1"));
    }

    @Test
    void writeStoresItsJobUnderTheSameKeyWhateverPicsureIdItsBodyCarries() throws Exception {
        List<AsyncResult> written = new ArrayList<>();
        doAnswer(invocation -> {
            AsyncResult job = (AsyncResult) invocation.callRealMethod();
            awaitSuccess(job);
            written.add(job);
            return job;
        }).when(queryService).runQuery(any(), any());

        service.writeQueryResult(query(ResultType.DATAFRAME_TIMESERIES, UUID.randomUUID(), "phs1.c1"), "phenotypic");
        service.writeQueryResult(query(ResultType.DATAFRAME_TIMESERIES, UUID.randomUUID(), "phs1.c1"), "phenotypic");

        UUID contentKey = QueryV3Service.contentKeyFor(query(ResultType.DATAFRAME_TIMESERIES, null, "phs1.c1"));
        assertEquals(contentKey.toString(), written.get(0).getId());
        assertEquals(contentKey.toString(), written.get(1).getId());
    }

    /**
     * Calls the write endpoint and, between its submit and its copy, has one other submit take over the key the write job is stored under
     * and another take the {@code picsureId} of the write body. Asserts the file written is the one of the job the write submitted, so a
     * lookup of the job by either key at copy time fails this.
     */
    private void assertWriteCopiesTheFileOfTheJobItSubmitted(String dataType, ResultType resultType, String fileName) throws Exception {
        UUID picsureId = UUID.randomUUID();
        Query written = query(resultType, picsureId, "phs1.c1");
        UUID writeKey = QueryV3Service.contentKeyFor(written);
        AsyncResult[] jobs = new AsyncResult[3];
        doAnswer(invocation -> {
            jobs[0] = (AsyncResult) invocation.callRealMethod();
            awaitSuccess(jobs[0]);
            jobs[1] = queryService.runQuery(query(resultType, writeKey, "phs2.c1"));
            awaitSuccess(jobs[1]);
            jobs[2] = queryService.runQuery(query(resultType, picsureId, "phs3.c1"));
            awaitSuccess(jobs[2]);
            return jobs[0];
        }).doCallRealMethod().when(queryService).runQuery(any(), any());

        ResponseEntity<String> response = service.writeQueryResult(written, dataType);

        assertNotSame(jobs[0], jobs[1]);
        assertSame(jobs[1], queryService.getResultFor(writeKey));
        assertSame(jobs[2], queryService.getResultFor(picsureId));
        assertEquals(200, response.getStatusCode().value());
        String file = Files.readString(sharingRoot.resolve(picsureId.toString()).resolve(fileName));
        assertTrue(file.contains("phs1.c1"), file);
        assertFalse(file.contains("phs2.c1"), file);
        assertFalse(file.contains("phs3.c1"), file);
        assertTrue(Files.exists(jobs[1].getTempFilePath()));
        assertTrue(Files.exists(jobs[2].getTempFilePath()));
    }

    /**
     * A processor run that writes one row per consent value of the query it is given, so a result file shows which query produced it. A
     * query that selects more than {@code SMALL_JOB_LIMIT} columns runs on the large executor and is held there until the test releases it.
     */
    private Answer<Void> writesTheConsentsOfTheQuery() {
        return invocation -> {
            Query executed = invocation.getArgument(0);
            AsyncResult job = invocation.getArgument(1);
            if (executed.select().size() > SMALL_JOB_LIMIT) {
                largeJobStarted.countDown();
                releaseLargeJob.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            List<String[]> rows = executed.authorizationFilters().stream().flatMap(filter -> filter.values().stream())
                .map(consent -> new String[] {consent}).toList();
            job.appendResults(rows);
            job.closeWriter();
            return null;
        };
    }

    private static void awaitSuccess(AsyncResult job) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (job.getStatus() != AsyncResult.Status.SUCCESS && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(AsyncResult.Status.SUCCESS, job.getStatus());
    }

    private static Query query(ResultType resultType, UUID picsureId, String consent) {
        return new Query(List.of("\\demographics\\AGE\\"), consents(consent), null, List.of(), resultType, picsureId, null);
    }

    private static Query largeQuery(String consent) {
        List<String> select = IntStream.rangeClosed(0, SMALL_JOB_LIMIT).mapToObj(column -> "\\column\\" + column + "\\").toList();
        return new Query(select, consents(consent), null, List.of(), ResultType.DATAFRAME_TIMESERIES, null, null);
    }

    private static List<AuthorizationFilter> consents(String consent) {
        return List.of(new AuthorizationFilter(AuthorizationFilter.CONSENTS_CONCEPT_PATH, Set.of(consent)));
    }
}
