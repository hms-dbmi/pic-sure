package edu.harvard.dbmi.avillach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.service.PicsureQueryService;
import edu.harvard.dbmi.avillach.service.AuditContext;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
public class PicsureQueryServiceTest extends BaseServiceTest {

    private UUID resourceId;
    private String queryString;
    private UUID queryId;
    private Query queryEntity;
    private QueryStatus results;

    @InjectMocks
    private PicsureQueryService queryService = new PicsureQueryService();

    @Mock
    private Resource mockResource = mock(Resource.class);

    @Mock
    private ResourceRepository resourceRepo = mock(ResourceRepository.class);

    @Mock
    private QueryRepository queryRepo = mock(QueryRepository.class);

    @Mock
    private ResourceWebClient webClient = mock(ResourceWebClient.class);

    @Mock
    private AuditContext auditContext = mock(AuditContext.class);

    @BeforeEach
    public void setUp() {
        resourceId = UUID.randomUUID();
        queryString = "queryDoesntMatterForTest";
        queryId = UUID.randomUUID();
        results = new QueryStatus();

        // Add needed data to results that are returned
        results.setResourceID(resourceId);
        results.setStatus(PicSureStatus.PENDING);
        results.setStartTime(new Date().getTime());

        // Return mocks when needed
        when(webClient.query(any(), any())).thenReturn(results);
        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);
        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");
        when(mockResource.getUuid()).thenReturn(resourceId);
        when(mockResource.getName()).thenReturn("test-resource");

        // Mock persisting the queryentity, so that it has an ID and we can test that
        // the correct information is stored in it
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Query query = invocation.getArgument(0);
                query.setUuid(queryId);
                queryEntity = query;
                return null;
            }
        }).when(queryRepo).persist(any(Query.class));
    }

    @Test
    public void testQueryEmptyRequest() {

        // Test missing query data
        try {
            QueryStatus result = queryService.query(null, null);
            fail("Missing query request info should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_DATA, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_DATA + "'"
            );
        }
    }

    @Test
    public void testQueryMissingResourceId() {

        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map
        // exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);

        // Test missing resourceId

        dataQueryRequest.setQuery(queryString);
        try {
            QueryStatus result = queryService.query(dataQueryRequest, null);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'"
            );
        }

    }

    @Test
    public void testQueryInvalidResourceId() {

        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map
        // exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);

        // Test nonexistent resourceId
        dataQueryRequest.setResourceUUID(UUID.randomUUID());
        try {
            QueryStatus result = queryService.query(dataQueryRequest, null);
            fail("Nonexistent resourceId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertTrue(
                e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND),
                "Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'"
            );
        }

    }

    @Test
    public void testQueryValidRequest() {
        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);
        dataQueryRequest.setResourceUUID(resourceId);
        dataQueryRequest.setQuery(queryString);

        QueryStatus result = queryService.query(dataQueryRequest, null);
        assertNotNull(result.getStatus(), "Status should not be null");
        assertNotNull(result.getResourceResultId(), "Resource result id should not be null");
        assertNotNull(result.getPicsureResultId(), "Picsure result id should not be null");
        // Since there was no resource result id, it should be the same as the picsure
        // result id
        assertEquals(
            result.getResourceResultId(), result.getPicsureResultId().toString(),
            "Resource result id and Picsure result id should match in case of no resource result id"
        );

        // Make sure the query is persisted
        assertNotNull(queryEntity, "Query Entity should have been persisted");
        assertEquals(queryEntity.getResource(), mockResource, "QueryEntity should be linked to resource");

        assertTrue(queryEntity.getQuery().contains(queryString), "Query Entity should have query stored");
        assertEquals(
            queryEntity.getResourceResultId(), queryEntity.getUuid().toString(),
            "Resource result id and Picsure result id should match in case of no resource result id"
        );

    }

    @Test
    public void testQueryStatusNoId() {

        GeneralQueryRequest statusRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        statusRequest.setResourceCredentials(clientCredentials);
        try {
            QueryStatus result = queryService.queryStatus(null, statusRequest, null);
            fail("Missing queryId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_QUERY_ID, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_QUERY_ID + "'"
            );
        }

    }

    @Test
    public void testQueryStatusInvalidId() {

        GeneralQueryRequest statusRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        statusRequest.setResourceCredentials(clientCredentials);

        // Nonexistent queryId
        try {
            QueryStatus result = queryService.queryStatus(UUID.randomUUID(), statusRequest, null);
            fail("Nonexistent queryId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertTrue(
                e.getContent().toString().contains(ProtocolException.QUERY_NOT_FOUND),
                "Error message should say '" + ProtocolException.QUERY_NOT_FOUND + "'"
            );
        }

    }

    @Test
    public void testQueryStatusValid() {

        GeneralQueryRequest statusRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        statusRequest.setResourceCredentials(clientCredentials);
        // Setup a pre-existing query Entity
        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.PENDING);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);

        results.setStatus(PicSureStatus.AVAILABLE); // this should update the DB entity status
        results.setStartTime(new Date().getTime());
        when(webClient.queryStatus(any(), any(), any())).thenReturn(results);

        // This one should work
        QueryStatus result = queryService.queryStatus(queryId, statusRequest, null);
        // These fields are set by the method
        assertNotNull(result, "Result should not be null");
        assertEquals(queryId, result.getPicsureResultId(), "Picsure ResultId should match");
        assertEquals(resourceId, result.getResourceID(), "Resource Id should match");
        assertNotNull(result.getStartTime(), "Start time should not be null");

        // Make sure info was saved to the query entity
        assertEquals(PicSureStatus.AVAILABLE, queryEntity.getStatus(), "Query status should have been updated");
    }

    @Test
    public void testQueryResultNoId() {


        GeneralQueryRequest resultRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        resultRequest.setResourceCredentials(clientCredentials);
        try {
            Response result = queryService.queryResult(null, resultRequest, null);
            fail("Missing queryId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_QUERY_ID, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_QUERY_ID + "'"
            );
        }

    }

    @Test
    public void testQueryResultInvalidId() {


        GeneralQueryRequest resultRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        resultRequest.setResourceCredentials(clientCredentials);
        try {
            Response result = queryService.queryResult(UUID.randomUUID(), resultRequest, null);
            fail("Nonexistent queryId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertTrue(
                e.getContent().toString().contains(ProtocolException.QUERY_NOT_FOUND),
                "Error message should say '" + ProtocolException.QUERY_NOT_FOUND + "'"
            );
        }


    }

    @Test
    public void testQueryResultValid() {


        GeneralQueryRequest resultRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        resultRequest.setResourceCredentials(clientCredentials);

        // Setup a pre-existing query Entity
        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.AVAILABLE);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);
        Response resp = mock(Response.class);
        when(webClient.queryResult(any(), any(), any())).thenReturn(resp);

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        // This one should work
        Response result = queryService.queryResult(queryId, resultRequest, null);
        assertNotNull(result, "Result should not be null");
    }

    @Test
    public void testQuerySyncNoQuery() {

        // Test missing query data
        try {
            Response result = queryService.querySync(null, null);
            fail("Missing query request info should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_DATA, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_DATA + "'"
            );
        }

    }

    @Test
    public void testQuerySyncNoResourceId() {

        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map
        // exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);

        // Test missing resourceId
        dataQueryRequest.setQuery(queryString);
        try {
            Response result = queryService.querySync(dataQueryRequest, null);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'"
            );
        }

    }

    @Test
    public void testQuerySyncInvalidResourceId() {

        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map
        // exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);
        // Test nonexistent resourceId
        dataQueryRequest.setResourceUUID(UUID.randomUUID());
        try {
            Response result = queryService.querySync(dataQueryRequest, null);
            fail("Nonexistent resourceId should throw an error");
        } catch (ApplicationException e) {
            assertNotNull(e.getContent());
            assertTrue(
                e.getContent().toString().contains(ApplicationException.MISSING_RESOURCE),
                "Error message should say '" + ApplicationException.MISSING_RESOURCE + "'"
            );
        }

    }

    @Test
    public void testQuerySyncValidNoResponseId() {

        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map
        // exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);
        dataQueryRequest.setQuery(queryString);

        // Add needed data to results that are returned
        results.setResourceID(resourceId);
        results.setStatus(PicSureStatus.AVAILABLE);
        results.setStartTime(new Date().getTime());

        // Return mocks when needed
        Response resp = mock(Response.class);
        when(webClient.querySync(any(), any(), any())).thenReturn(resp);

        // Mock persisting the queryentity, so that it has an ID and we can test that
        // the correct information is stored in it
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Query query = invocation.getArgument(0);
                query.setUuid(queryId);

                queryEntity = query;
                return null;
            }
        }).when(queryRepo).persist(any(Query.class));


        // Test correct request
        dataQueryRequest.setResourceUUID(resourceId);
        Response result = queryService.querySync(dataQueryRequest, null);
        assertNotNull(result.getStatus(), "Result should not be null");

        // Make sure the query is persisted
        assertNotNull(queryEntity, "Query Entity should have been persisted");
        assertEquals(queryEntity.getResource(), mockResource, "QueryEntity should be linked to resource");

        assertTrue(queryEntity.getQuery().contains(queryString), "Query Entity should have query stored");
        assertEquals(
            queryId.toString(), queryEntity.getResourceResultId(),
            "Resource result id and Picsure result id should match in case of no resource result id"
        );

    }

    @Test
    public void testShouldQueryMetadata() {
        Resource resource = new Resource();
        resource.setUuid(resourceId);
        String resultId = UUID.randomUUID().toString();
        Query query = new Query();
        query.setQuery("{}");
        query.setResource(resource);
        query.setStatus(PicSureStatus.AVAILABLE);
        query.setStartTime(new java.sql.Date(System.currentTimeMillis()));
        query.setResourceResultId(resultId);

        String metaData = "{\\\"picsureQueryId\\\":\\\"b9e6cea7-142e-5859-8e98-1245c959fc0b\\\","
            + "\\\"commonAreaId\\\":\\\"290a9d2e-1a20-4407-b351-499185f5554e\\\"}";

        query.setMetadata(metaData.getBytes(StandardCharsets.UTF_8));

        Mockito.when(queryRepo.getById(query.getUuid())).thenReturn(query);

        QueryStatus status = queryService.queryMetadata(query.getUuid(), null);
        String actual = (String) status.getResultMetadata().get("queryResultMetadata");
        assertEquals(metaData, actual);
    }

    @Test
    public void testQuerySyncValidWithResponseId() {

        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        // At this level we don't check the credentials themselves, just that the map
        // exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);
        dataQueryRequest.setQuery(queryString);

        String resultId = UUID.randomUUID().toString();

        // Add needed data to results that are returned
        results.setResourceID(resourceId);
        results.setStatus(PicSureStatus.AVAILABLE);
        results.setStartTime(new Date().getTime());
        results.setResourceResultId(resultId);

        // Return mocks when needed
        Response resp = mock(Response.class);

        MultivaluedMap<String, Object> headerMap = new MultivaluedHashMap<String, Object>();
        headerMap.add(ResourceWebClient.QUERY_METADATA_FIELD, resultId);
        when(resp.getHeaders()).thenReturn(headerMap);
        when(webClient.querySync(any(), any(), any())).thenReturn(resp);

        // Mock persisting the queryentity, so that it has an ID and we can test that
        // the correct information is stored in it
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Query query = invocation.getArgument(0);
                query.setUuid(queryId);
                queryEntity = query;
                return null;
            }
        }).when(queryRepo).persist(any(Query.class));


        // Test correct request
        dataQueryRequest.setResourceUUID(resourceId);
        Response result = queryService.querySync(dataQueryRequest, null);
        assertNotNull(result.getStatus(), "Result should not be null");

        // Make sure the query is persisted
        assertNotNull(queryEntity, "Query Entity should have been persisted");
        assertEquals(queryEntity.getResource(), mockResource, "QueryEntity should be linked to resource");

        assertTrue(queryEntity.getQuery().contains(queryString), "Query Entity should have query stored");
        assertEquals(resultId, queryEntity.getResourceResultId(), "Resource result id should match returned header");

    }

    @Test
    public void testQuerySetsAuditContext() {
        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        dataQueryRequest.setResourceCredentials(new HashMap<>());
        dataQueryRequest.setResourceUUID(resourceId);
        dataQueryRequest.setQuery(queryString);

        queryService.query(dataQueryRequest, null);

        verify(auditContext).put("resource_id", resourceId.toString());
        verify(auditContext).put("resource_name", "test-resource");
        verify(auditContext).put(eq("query_id"), any(String.class));
    }

    @Test
    public void testQuerySyncSetsAuditContext() {
        GeneralQueryRequest dataQueryRequest = new GeneralQueryRequest();
        dataQueryRequest.setResourceCredentials(new HashMap<>());
        dataQueryRequest.setResourceUUID(resourceId);
        dataQueryRequest.setQuery(queryString);

        Response resp = mock(Response.class);
        when(webClient.querySync(any(), any(), any())).thenReturn(resp);

        queryService.querySync(dataQueryRequest, null);

        verify(auditContext).put("resource_id", resourceId.toString());
        verify(auditContext).put("resource_name", "test-resource");
        verify(auditContext).put(eq("query_id"), any(String.class));
    }

    @Test
    public void testQueryResultSetsAuditContext() {
        GeneralQueryRequest resultRequest = new GeneralQueryRequest();
        resultRequest.setResourceCredentials(new HashMap<>());

        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.AVAILABLE);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);
        Response resp = mock(Response.class);
        when(webClient.queryResult(any(), any(), any())).thenReturn(resp);

        queryService.queryResult(queryId, resultRequest, null);

        verify(auditContext).put("resource_id", resourceId.toString());
        verify(auditContext).put("resource_name", "test-resource");
        verify(auditContext).put("query_id", queryId.toString());
    }

    @Test
    public void testQuerySignedUrlSetsAuditContext() {
        GeneralQueryRequest resultRequest = new GeneralQueryRequest();
        resultRequest.setResourceCredentials(new HashMap<>());

        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.AVAILABLE);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);
        Response resp = mock(Response.class);
        when(webClient.queryResultSignedUrl(any(), any(), any())).thenReturn(resp);

        queryService.queryResultSignedUrl(queryId, resultRequest, null);

        verify(auditContext).put("resource_id", resourceId.toString());
        verify(auditContext).put("resource_name", "test-resource");
        verify(auditContext).put("query_id", queryId.toString());
    }
}
