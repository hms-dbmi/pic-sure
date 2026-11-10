package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.AsyncResult;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PicSureV3ServiceStatusTest {

    private PicSureV3Service service;
    private QueryV3Service queryService;
    private CountV3Processor countProcessor;
    private SignUrlService signUrlService;

    @BeforeEach
    void setup() {
        queryService = mock(QueryV3Service.class);
        countProcessor = mock(CountV3Processor.class);
        signUrlService = mock(SignUrlService.class);
        service = new PicSureV3Service(
            queryService, countProcessor, mock(VariantListV3Processor.class), mock(QueryExecutor.class), mock(Paginator.class),
            signUrlService, mock(FileSharingV3Service.class), mock(TestDataService.class)
        );
        ReflectionTestUtils.setField(service, "httpRequest", new MockHttpServletRequest());
    }

    @Test
    void statusOfAnUnknownIdIs404() {
        UUID queryId = UUID.randomUUID();
        when(queryService.getStatusFor(queryId.toString())).thenReturn(null);

        ResponseEntity<QueryStatus> response = service.queryStatus(queryId, mock(QueryRequest.class));

        assertEquals(404, response.getStatusCode().value());
        assertFalse(response.hasBody());
    }

    @Test
    void statusOfAKnownIdIs200WithTheJobStatus() {
        UUID queryId = UUID.randomUUID();
        AsyncResult job = jobWithStatus(AsyncResult.Status.RUNNING);
        when(job.getId()).thenReturn(queryId.toString());
        Query query = mock(Query.class);
        when(job.getQuery()).thenReturn(query);
        when(queryService.getStatusFor(queryId.toString())).thenReturn(job);

        ResponseEntity<QueryStatus> response = service.queryStatus(queryId, mock(QueryRequest.class));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("RUNNING", response.getBody().getResourceStatus());
        assertEquals(queryId.toString(), response.getBody().getResourceResultId());
    }

    @ParameterizedTest
    @EnumSource(value = AsyncResult.Status.class, names = {"RUNNING", "PENDING", "RETRY"})
    void resultOfARunningJobIs409(AsyncResult.Status status) {
        UUID queryId = UUID.randomUUID();
        AsyncResult job = jobWithStatus(status);
        when(queryService.getResultFor(queryId)).thenReturn(job);

        ResponseEntity<?> response = service.queryResult(queryId, mock(QueryRequest.class));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("Status : " + status.name(), response.getBody());
        verify(job, never()).open();
    }

    @Test
    void resultOfAnErroredJobIs400() {
        UUID queryId = UUID.randomUUID();
        AsyncResult job = jobWithStatus(AsyncResult.Status.ERROR);
        when(queryService.getResultFor(queryId)).thenReturn(job);

        ResponseEntity<?> response = service.queryResult(queryId, mock(QueryRequest.class));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Status : ERROR", response.getBody());
    }

    @Test
    void resultOfAnUnknownIdIs404() {
        UUID queryId = UUID.randomUUID();
        when(queryService.getResultFor(queryId)).thenReturn(null);

        assertEquals(404, service.queryResult(queryId, mock(QueryRequest.class)).getStatusCode().value());
    }

    @ParameterizedTest
    @EnumSource(value = AsyncResult.Status.class, names = {"RUNNING", "PENDING", "RETRY"})
    void signedUrlOfARunningJobIs409(AsyncResult.Status status) throws IOException {
        UUID queryId = UUID.randomUUID();
        AsyncResult job = jobWithStatus(status);
        when(queryService.getResultFor(queryId)).thenReturn(job);

        ResponseEntity<?> response = service.querySignedURL(queryId, mock(QueryRequest.class));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("Status : " + status.name(), response.getBody());
        verify(signUrlService, never()).uploadFile(any(), any());
    }

    @Test
    void signedUrlOfAnErroredJobIs400() throws IOException {
        UUID queryId = UUID.randomUUID();
        AsyncResult job = jobWithStatus(AsyncResult.Status.ERROR);
        when(queryService.getResultFor(queryId)).thenReturn(job);

        ResponseEntity<?> response = service.querySignedURL(queryId, mock(QueryRequest.class));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Status : ERROR", response.getBody());
    }

    @Test
    void signedUrlOfAnUnknownIdIs404() throws IOException {
        UUID queryId = UUID.randomUUID();
        when(queryService.getResultFor(queryId)).thenReturn(null);

        assertEquals(404, service.querySignedURL(queryId, mock(QueryRequest.class)).getStatusCode().value());
    }

    @Test
    void syncReturnsPicsureIdInQueryMetadata() {
        UUID picsureId = UUID.randomUUID();
        when(countProcessor.runCounts(any())).thenReturn(7);

        ResponseEntity<?> response = sync(Map.of("expectedResultType", "COUNT", "picsureId", picsureId.toString()));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(picsureId.toString(), response.getHeaders().getFirst("queryMetadata"));
    }

    @Test
    void syncWithoutPicsureIdStillReturnsAnIdInQueryMetadata() {
        when(countProcessor.runCounts(any())).thenReturn(7);

        ResponseEntity<?> response = sync(Map.of("expectedResultType", "COUNT"));

        String header = response.getHeaders().getFirst("queryMetadata");
        assertNotNull(header);
        assertNotNull(UUID.fromString(header));
    }

    private ResponseEntity<?> sync(Map<String, Object> queryBody) {
        QueryRequest request = mock(QueryRequest.class);
        when(request.getQuery()).thenReturn(queryBody);
        try (MockedStatic<Crypto> crypto = mockStatic(Crypto.class)) {
            crypto.when(() -> Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)).thenReturn(true);
            return service.querySync(request);
        }
    }

    private static AsyncResult jobWithStatus(AsyncResult.Status status) {
        AsyncResult job = mock(AsyncResult.class);
        when(job.getStatus()).thenReturn(status);
        return job;
    }
}
