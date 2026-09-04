package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the unbounded synchronous polling loop: a query that never leaves {@code RUNNING} must give up at the configured
 * deadline instead of pinning the request thread forever.
 */
@SpringBootTest
@TestPropertySource(
    properties = {"hpds.query.sync.poll-initial-delay=PT0.001S", "hpds.query.sync.poll-max-delay=PT0.005S",
        "hpds.query.sync.timeout=PT0.15S"}
)
class PicSureV3ServiceSyncWaitTest {

    @Autowired
    PicSureV3Service subject;

    @MockBean
    QueryV3Service queryService;
    @MockBean
    CountV3Processor countProcessor;
    @MockBean
    VariantListV3Processor variantListProcessor;
    @MockBean
    QueryExecutor queryExecutor;
    @MockBean
    Paginator paginator;
    @MockBean
    SignUrlService signUrlService;
    @MockBean
    FileSharingV3Service fileSharingService;
    @MockBean
    TestDataService testDataService;

    /** {@code querySync} short-circuits to 403 unless a crypto key is loaded, so install a throwaway one. */
    @BeforeAll
    static void loadCryptoKey() throws Exception {
        Path keyFile = Files.createTempFile("hpds-test-key", ".bin");
        Files.writeString(keyFile, "0123456789abcdef");
        Crypto.loadKey(Crypto.DEFAULT_KEY_NAME, keyFile.toString());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    void querySyncGivesUpOnAQueryThatNeverCompletes() throws Exception {
        // The audit attributes the controller writes are request-scoped, and the timeout above runs the body on its own thread.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        UUID queryId = UUID.randomUUID();
        AsyncResult running = runningResult(queryId);
        Mockito.when(queryService.runQuery(Mockito.any())).thenReturn(running);
        Mockito.when(queryService.getStatusFor(queryId.toString())).thenReturn(running);

        ResponseEntity<?> response = subject.querySync(dataframeRequest());

        assertEquals(504, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody()).contains(queryId.toString()), "the 504 body must name the abandoned query");
        Mockito.verify(queryService, Mockito.never()).getResultFor(Mockito.any());
    }

    private static AsyncResult runningResult(UUID queryId) {
        Query query = Mockito.mock(Query.class);
        Mockito.when(query.id()).thenReturn(queryId);
        AsyncResult result = Mockito.mock(AsyncResult.class);
        Mockito.when(result.getStatus()).thenReturn(AsyncResult.Status.RUNNING);
        Mockito.when(result.getId()).thenReturn(queryId.toString());
        Mockito.when(result.getQuery()).thenReturn(query);
        return result;
    }

    private static QueryRequest dataframeRequest() {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setQuery(Map.of("expectedResultType", ResultType.DATAFRAME.name()));
        return request;
    }
}
