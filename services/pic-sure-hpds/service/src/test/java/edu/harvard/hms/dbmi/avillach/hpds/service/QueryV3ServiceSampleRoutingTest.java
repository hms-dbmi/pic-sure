package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryV3ServiceSampleRoutingTest {

    private static final int SMALL_JOB_LIMIT = 100;

    private final CountDownLatch workerStarted = new CountDownLatch(1);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    private LoggingClient loggingClient;
    private QueryV3Service service;

    @BeforeEach
    void setup() {
        MultiValueQueryV3Processor multiValueProcessor = mock(MultiValueQueryV3Processor.class);
        when(multiValueProcessor.getHeaderRow(any())).thenReturn(new String[] {"patient_id"});
        doAnswer(invocation -> {
            workerStarted.countDown();
            releaseWorker.await(30, TimeUnit.SECONDS);
            return null;
        }).when(multiValueProcessor).runQuery(any(), any());
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        service = new QueryV3Service(
            mock(TimeseriesV3Processor.class), mock(CountV3Processor.class), multiValueProcessor, null, mock(QueryValidator.class),
            SMALL_JOB_LIMIT, 1, 1, mock(PatientV3Processor.class), loggingClient
        );
    }

    @AfterEach
    void shutdown() {
        releaseWorker.countDown();
        executor("smallTaskExecutor").shutdownNow();
        executor("largeTaskExecutor").shutdownNow();
    }

    @Test
    void sampleJobWithManyColumnsGoesToTheSmallExecutor() throws IOException {
        AsyncResult result = service.runQuery(query(SMALL_JOB_LIMIT + 1, "sample", true));

        assertSame(executor("smallTaskExecutor"), result.getJobQueue());
        assertEquals("small", enqueuedQueueValue());
    }

    @Test
    void fullJobWithManyColumnsStillGoesToTheLargeExecutor() throws IOException {
        AsyncResult result = service.runQuery(query(SMALL_JOB_LIMIT + 1, "full", false));

        assertSame(executor("largeTaskExecutor"), result.getJobQueue());
        assertEquals("large", enqueuedQueueValue());
    }

    @Test
    void sampleJobWithManyColumnsReportsTheSmallQueueDepth() throws Exception {
        service.runQuery(query(1, "running", false));
        assertTrue(workerStarted.await(30, TimeUnit.SECONDS));
        service.runQuery(query(1, "waiting", false));

        AsyncResult sample = service.runQuery(query(SMALL_JOB_LIMIT + 1, "sample", true));

        assertEquals(2, service.getStatusFor(sample.getId()).getQueueDepth());
    }

    private String enqueuedQueueValue() {
        ArgumentCaptor<LoggingEvent> events = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient, atLeastOnce()).send(events.capture());
        return events.getAllValues().stream().filter(event -> "query.enqueued".equals(event.getAction())).findFirst().orElseThrow()
            .getMetadata().get("queue").toString();
    }

    private ExecutorService executor(String field) {
        return (ExecutorService) ReflectionTestUtils.getField(service, field);
    }

    private static Query query(int columns, String prefix, boolean sample) {
        List<String> select = IntStream.range(0, columns).mapToObj(column -> "\\" + prefix + "\\" + column + "\\").toList();
        return new Query(select, List.of(), null, List.of(), ResultType.DATAFRAME, null, null, sample);
    }
}
