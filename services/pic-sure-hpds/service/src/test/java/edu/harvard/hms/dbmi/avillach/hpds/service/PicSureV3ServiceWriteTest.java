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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PicSureV3ServiceWriteTest {

    private static final long WAIT_SECONDS = 30;

    @TempDir
    Path sharingRoot;

    private QueryV3Service queryService;
    private PicSureV3Service service;

    @BeforeEach
    void setup() {
        TimeseriesV3Processor timeseriesProcessor = mock(TimeseriesV3Processor.class);
        when(timeseriesProcessor.getHeaderRow(any())).thenReturn(new String[] {"consent"});
        doAnswer(invocation -> {
            Query executed = invocation.getArgument(0);
            AsyncResult job = invocation.getArgument(1);
            List<String[]> rows = executed.authorizationFilters().stream().flatMap(filter -> filter.values().stream())
                .map(consent -> new String[] {consent}).toList();
            job.appendResults(rows);
            job.closeWriter();
            return null;
        }).when(timeseriesProcessor).runQuery(any(), any());
        queryService = spy(
            new QueryV3Service(
                timeseriesProcessor, mock(CountV3Processor.class), mock(MultiValueQueryV3Processor.class), null, mock(QueryValidator.class),
                100, 1, 1, mock(PatientV3Processor.class), mock(LoggingClient.class)
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
        ((ExecutorService) ReflectionTestUtils.getField(queryService, "smallTaskExecutor")).shutdownNow();
        ((ExecutorService) ReflectionTestUtils.getField(queryService, "largeTaskExecutor")).shutdownNow();
    }

    @Test
    void writeCopiesTheFileOfTheJobItSubmittedWhenAnotherSubmitTakesOverTheId() throws Exception {
        UUID picsureId = UUID.randomUUID();
        AsyncResult[] jobs = new AsyncResult[2];
        doAnswer(invocation -> {
            jobs[0] = (AsyncResult) invocation.callRealMethod();
            awaitSuccess(jobs[0]);
            jobs[1] = queryService.runQuery(query(picsureId, "phs2.c1"));
            awaitSuccess(jobs[1]);
            return jobs[0];
        }).doCallRealMethod().when(queryService).runQuery(any());

        ResponseEntity<String> response = service.writeQueryResult(query(picsureId, "phs1.c1"), "phenotypic");

        assertNotSame(jobs[0], jobs[1]);
        assertEquals(jobs[1], queryService.getResultFor(picsureId));
        assertEquals(200, response.getStatusCode().value());
        String written = Files.readString(sharingRoot.resolve(picsureId.toString()).resolve("phenotypic_data.csv"));
        assertTrue(written.contains("phs1.c1"), written);
        assertFalse(written.contains("phs2.c1"), written);
        assertTrue(Files.exists(jobs[1].getTempFilePath()));
    }

    private static void awaitSuccess(AsyncResult job) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (job.getStatus() != AsyncResult.Status.SUCCESS && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(AsyncResult.Status.SUCCESS, job.getStatus());
    }

    private static Query query(UUID picsureId, String consent) {
        List<AuthorizationFilter> filters = List.of(new AuthorizationFilter(AuthorizationFilter.CONSENTS_CONCEPT_PATH, Set.of(consent)));
        return new Query(List.of("\\demographics\\AGE\\"), filters, null, List.of(), ResultType.DATAFRAME_TIMESERIES, picsureId, null);
    }
}
