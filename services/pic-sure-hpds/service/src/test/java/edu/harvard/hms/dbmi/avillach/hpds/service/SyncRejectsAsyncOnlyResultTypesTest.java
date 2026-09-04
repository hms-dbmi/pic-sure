package edu.harvard.hms.dbmi.avillach.hpds.service;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.processing.upload.SignUrlService;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.QueryExecutor;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.VariantListV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.FileSharingV3Service;
import edu.harvard.hms.dbmi.avillach.hpds.service.filesharing.TestDataService;
import edu.harvard.hms.dbmi.avillach.hpds.service.util.Paginator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Result types backed by an asynchronous HPDS job are not served on {@code /query/sync}. They are submitted through {@code POST /query} and
 * collected through {@code /query/{id}/status} and {@code /query/{id}/result}, which is what the frontend export flow and the Python
 * adapter's {@code export_pfb} already do. Serving them synchronously meant the request thread polled the job to completion, so one queued
 * query held a servlet worker for as long as it stayed queued.
 */
@SpringBootTest
class SyncRejectsAsyncOnlyResultTypesTest {

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

    @ParameterizedTest
    @EnumSource(value = ResultType.class, names = {"DATAFRAME", "DATAFRAME_TIMESERIES", "PATIENTS"})
    void syncRefusesAnAsyncOnlyResultTypeWithoutSubmittingAQuery(ResultType resultType) throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        ResponseEntity<?> response = subject.querySync(requestFor(resultType));

        assertEquals(400, response.getStatusCode().value());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains(resultType.name()), "the refusal must name the result type: " + body);
        assertTrue(body.contains("/query"), "the refusal must point at the asynchronous endpoints: " + body);
        Mockito.verify(queryService, Mockito.never()).runQuery(Mockito.any());
        Mockito.verify(queryService, Mockito.never()).getResultFor(Mockito.any());
        Mockito.verify(queryService, Mockito.never()).getStatusFor(Mockito.any());
    }

    @Test
    void syncStillServesTheResultTypesItComputesDirectly() throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Mockito.when(countProcessor.runCrossCounts(Mockito.any())).thenReturn(Map.of("\\demographics\\SEX\\", 42));

        ResponseEntity<?> response = subject.querySync(requestFor(ResultType.CROSS_COUNT));

        assertEquals(200, response.getStatusCode().value());
        Mockito.verify(queryService, Mockito.never()).runQuery(Mockito.any());
    }

    private static GeneralQueryRequest requestFor(ResultType resultType) {
        GeneralQueryRequest request = new GeneralQueryRequest();
        request.setQuery(Map.of("expectedResultType", resultType.name()));
        return request;
    }
}
