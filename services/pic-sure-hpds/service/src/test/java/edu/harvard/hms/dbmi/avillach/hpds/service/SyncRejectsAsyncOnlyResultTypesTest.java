package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.AbstractProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.CountProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.VariantListProcessor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingService;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Result types backed by an asynchronous HPDS job are not served on {@code /query/sync}. They are submitted through {@code POST /query} and
 * collected through {@code /query/{id}/status} and {@code /query/{id}/result}, which is what the frontend export flow and the Python
 * adapter's {@code export_pfb} already do. Serving them synchronously meant the request thread polled the job to completion, so one queued
 * query held a servlet worker for as long as it stayed queued.
 */
class SyncRejectsAsyncOnlyResultTypesTest {

    private PicSureService v1;
    private QueryService v1QueryService;
    private CountProcessor v1CountProcessor;

    private PicSureV3Service v3;
    private QueryV3Service v3QueryService;
    private CountV3Processor v3CountProcessor;

    @BeforeEach
    void setup() {
        v1QueryService = mock(QueryService.class);
        v1CountProcessor = mock(CountProcessor.class);
        v1 = new PicSureService(
            v1QueryService, v1CountProcessor, mock(VariantListProcessor.class), mock(AbstractProcessor.class), mock(Paginator.class),
            mock(SignUrlService.class), mock(FileSharingService.class), mock(TestDataService.class)
        );
        ReflectionTestUtils.setField(v1, "httpRequest", new MockHttpServletRequest());

        v3QueryService = mock(QueryV3Service.class);
        v3CountProcessor = mock(CountV3Processor.class);
        v3 = new PicSureV3Service(
            v3QueryService, v3CountProcessor, mock(VariantListV3Processor.class), mock(QueryExecutor.class), mock(Paginator.class),
            mock(SignUrlService.class), mock(FileSharingV3Service.class), mock(TestDataService.class)
        );
        ReflectionTestUtils.setField(v3, "httpRequest", new MockHttpServletRequest());
    }

    @ParameterizedTest
    @EnumSource(value = ResultType.class, names = {"DATAFRAME", "DATAFRAME_TIMESERIES", "PATIENTS", "DATAFRAME_PFB"})
    void v3SyncRefusesAnAsyncOnlyResultTypeWithoutSubmittingAQuery(ResultType resultType) throws IOException {
        ResponseEntity<?> response = withKey(() -> v3.querySync(requestFor(resultType)));

        assertRefusal(response, resultType);
        verify(v3QueryService, never()).runQuery(any());
        verify(v3QueryService, never()).getResultFor(any());
        verify(v3QueryService, never()).getStatusFor(any());
    }

    @ParameterizedTest
    @EnumSource(value = ResultType.class, names = {"DATAFRAME", "DATAFRAME_TIMESERIES", "PATIENTS", "DATAFRAME_PFB"})
    void v1SyncRefusesAnAsyncOnlyResultTypeWithoutSubmittingAQuery(ResultType resultType) throws IOException {
        ResponseEntity<?> response = withKey(() -> v1.querySync(requestFor(resultType)));

        assertRefusal(response, resultType);
        verify(v1QueryService, never()).runQuery(any());
        verify(v1QueryService, never()).getResultFor(any());
        verify(v1QueryService, never()).getStatusFor(any());
    }

    @Test
    void v3SyncStillServesTheResultTypesItComputesDirectly() throws IOException {
        when(v3CountProcessor.runCrossCounts(any())).thenReturn(Map.of("\\demographics\\SEX\\", 42));

        ResponseEntity<?> response = withKey(() -> v3.querySync(requestFor(ResultType.CROSS_COUNT)));

        assertEquals(200, response.getStatusCode().value());
        verify(v3QueryService, never()).runQuery(any());
    }

    @Test
    void v1SyncStillServesTheResultTypesItComputesDirectly() throws IOException {
        when(v1CountProcessor.runCrossCounts(any())).thenReturn(Map.of("\\demographics\\SEX\\", 42));

        ResponseEntity<?> response = withKey(() -> v1.querySync(requestFor(ResultType.CROSS_COUNT)));

        assertEquals(200, response.getStatusCode().value());
        verify(v1QueryService, never()).runQuery(any());
    }

    private static void assertRefusal(ResponseEntity<?> response, ResultType resultType) {
        assertEquals(400, response.getStatusCode().value());
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType(), "a bare sentence must not claim to be JSON");
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains(resultType.name()), "the refusal must name the result type: " + body);
        assertTrue(body.contains("/query"), "the refusal must point at the asynchronous endpoints: " + body);
    }

    private static ResponseEntity<?> withKey(Supplier<ResponseEntity> call) {
        try (MockedStatic<Crypto> crypto = mockStatic(Crypto.class)) {
            crypto.when(() -> Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)).thenReturn(true);
            return call.get();
        }
    }

    private static GeneralQueryRequest requestFor(ResultType resultType) {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setQuery(Map.of("expectedResultType", resultType.name()));
        return request;
    }
}
