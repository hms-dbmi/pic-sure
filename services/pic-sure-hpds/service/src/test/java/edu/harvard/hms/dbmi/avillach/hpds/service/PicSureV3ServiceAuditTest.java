package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.hpds.crypto.Crypto;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.hpds.processing.audit.AuditAttributes;
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
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

class PicSureV3ServiceAuditTest {

    private PicSureV3Service service;
    private QueryV3Service queryService;
    private QueryExecutor queryExecutor;
    private MockHttpServletRequest request;

    @BeforeEach
    void setup() {
        queryService = mock(QueryV3Service.class);
        queryExecutor = mock(QueryExecutor.class);
        service = new PicSureV3Service(
            queryService,
            mock(CountV3Processor.class),
            mock(VariantListV3Processor.class),
            queryExecutor,
            mock(Paginator.class),
            mock(SignUrlService.class),
            mock(FileSharingV3Service.class),
            mock(TestDataService.class)
        );

        request = new MockHttpServletRequest();
        ReflectionTestUtils.setField(service, "httpRequest", request);
    }

    @Test
    void queryPopulatesResultTypeAndQueryId() throws IOException {
        AsyncResult asyncResult = mock(AsyncResult.class);
        when(asyncResult.getId()).thenReturn("query-123");
        when(asyncResult.getStatus()).thenReturn(AsyncResult.Status.RUNNING);
        when(asyncResult.getQueuedTime()).thenReturn(1000L);
        when(asyncResult.getCompletedTime()).thenReturn(0L);
        Query mockQuery = mock(Query.class);
        when(asyncResult.getQuery()).thenReturn(mockQuery);
        when(mockQuery.id()).thenReturn(UUID.randomUUID());
        when(queryService.runQuery(any())).thenReturn(asyncResult);

        QueryRequest queryRequest = mock(QueryRequest.class);
        when(queryRequest.getQuery()).thenReturn(Map.of("expectedResultType", "COUNT"));

        try (MockedStatic<Crypto> crypto = mockStatic(Crypto.class)) {
            crypto.when(() -> Crypto.hasKey(Crypto.DEFAULT_KEY_NAME)).thenReturn(true);
            service.query(queryRequest);
        }

        Map<String, Object> metadata = AuditAttributes.getMetadata(request);
        assertEquals("query-123", metadata.get("query_id"));
        assertEquals("COUNT", metadata.get("result_type"));
    }

    @Test
    void queryResultPopulatesQueryId() {
        UUID queryId = UUID.randomUUID();
        when(queryService.getResultFor(queryId)).thenReturn(null);

        service.queryResult(queryId, mock(QueryRequest.class));

        assertEquals(queryId.toString(), AuditAttributes.getMetadata(request).get("query_id"));
    }

    @Test
    void queryStatusPopulatesQueryId() {
        UUID queryId = UUID.randomUUID();
        AsyncResult asyncResult = mock(AsyncResult.class);
        when(asyncResult.getStatus()).thenReturn(AsyncResult.Status.RUNNING);
        when(asyncResult.getQueuedTime()).thenReturn(1000L);
        when(asyncResult.getCompletedTime()).thenReturn(0L);
        Query mockQuery = mock(Query.class);
        when(asyncResult.getQuery()).thenReturn(mockQuery);
        when(mockQuery.id()).thenReturn(UUID.randomUUID());
        when(queryService.getStatusFor(queryId.toString())).thenReturn(asyncResult);

        service.queryStatus(queryId, mock(QueryRequest.class));

        assertEquals(queryId.toString(), AuditAttributes.getMetadata(request).get("query_id"));
    }

    @Test
    void querySignedUrlPopulatesQueryId() throws IOException {
        UUID queryId = UUID.randomUUID();
        when(queryService.getResultFor(queryId)).thenReturn(null);

        service.querySignedURL(queryId, mock(QueryRequest.class));

        assertEquals(queryId.toString(), AuditAttributes.getMetadata(request).get("query_id"));
    }

    @Test
    void writeQueryResultPopulatesDataTypeAndResultType() {
        Query query = mock(Query.class);
        when(query.expectedResultType()).thenReturn(ResultType.COUNT);
        when(query.picsureId()).thenReturn(UUID.randomUUID());

        service.writeQueryResult(query, "phenotypic");

        Map<String, Object> metadata = AuditAttributes.getMetadata(request);
        assertEquals("phenotypic", metadata.get("data_type"));
        assertEquals("COUNT", metadata.get("result_type"));
    }

    @Test
    void searchPopulatesSearchTerm() {
        when(queryExecutor.getDictionary()).thenReturn(new TreeMap<>());
        when(queryExecutor.getInfoStoreMeta()).thenReturn(List.of());

        QueryRequest searchRequest = mock(QueryRequest.class);
        when(searchRequest.getQuery()).thenReturn("blood pressure");

        service.search(searchRequest);

        assertEquals("blood pressure", AuditAttributes.getMetadata(request).get("search_term"));
    }

    @Test
    void searchGenomicConceptValuesPopulatesConceptPath() {
        when(queryExecutor.searchInfoConceptValues(anyString(), anyString())).thenReturn(List.of("val1"));

        Paginator paginator = new Paginator();
        PicSureV3Service serviceWithRealPaginator = new PicSureV3Service(
            queryService, mock(CountV3Processor.class), mock(VariantListV3Processor.class),
            queryExecutor, paginator, mock(SignUrlService.class), mock(FileSharingV3Service.class),
            mock(TestDataService.class)
        );

        ReflectionTestUtils.setField(serviceWithRealPaginator, "httpRequest", request);
        serviceWithRealPaginator.searchGenomicConceptValues("\\gene\\BRCA1\\", "val", 1, 10);

        assertEquals("\\gene\\BRCA1\\", AuditAttributes.getMetadata(request).get("genomic_concept_path"));
    }
}
