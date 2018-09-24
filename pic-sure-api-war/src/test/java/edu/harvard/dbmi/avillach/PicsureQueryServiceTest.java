package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.service.PicsureQueryService;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.PicSureStatus;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import javax.ws.rs.core.Response;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class PicsureQueryServiceTest extends BaseServiceTest {

    private UUID resourceId = UUID.randomUUID();
    private String queryString = "queryDoesntMatterForTest";
    private UUID queryId = UUID.randomUUID();
    private Query queryEntity;
    private QueryStatus results = new QueryStatus();

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

    @Test
    public void testQuery() {

        /* SET UP MOCKS */
        //Add needed data to results that are returned
        results.setResourceID(resourceId);
        results.setStatus(PicSureStatus.PENDING);
        results.setStartTime(new Date().getTime());

        //Return mocks when needed
        when(webClient.query(any(), any())).thenReturn(results);
        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);

        //Mock persisting the queryentity, so that it has an ID and we can test that the correct information is stored in it
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Query query = invocation.getArgument(0);
                query.setUuid(queryId);
                queryEntity = query;
                return null;
            }
        }).when(queryRepo).persist(any(Query.class));

        /* END SET UP */

        //Test missing query data
        try {
            QueryStatus result = queryService.query(null);
            fail("Missing query request info should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_DATA + "'", ProtocolException.MISSING_DATA, e.getContent().toString());
        }

        QueryRequest dataQueryRequest = new QueryRequest();
        //At this level we don't check the credentials themselves, just that the map exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);

        //Test missing resourceId
        dataQueryRequest.setQuery(queryString);
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'", ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString());
        }

        //Test nonexistent resourceId
        dataQueryRequest.setResourceUUID(UUID.randomUUID());
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Nonexistent resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'", e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND));
        }

        //Test missing targetURL
        dataQueryRequest.setResourceUUID(resourceId);
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_TARGET_URL + "'", ApplicationException.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        dataQueryRequest.setResourceUUID(resourceId);
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //Test correct request
        dataQueryRequest.setResourceUUID(resourceId);
        QueryStatus result = queryService.query(dataQueryRequest);
        assertNotNull("Status should not be null", result.getStatus());
        assertNotNull("Resource result id should not be null", result.getResourceResultId());
        assertNotNull("Picsure result id should not be null", result.getPicsureResultId());
        //Since there was no resource result id, it should be the same as the picsure result id
        assertEquals("Resource result id and Picsure result id should match in case of no resource result id", result.getResourceResultId(), result.getPicsureResultId().toString());

        //Make sure the query is persisted
        assertNotNull("Query Entity should have been persisted", queryEntity);
        assertEquals("QueryEntity should be linked to resource", queryEntity.getResource(), mockResource);
        assertEquals("Query Entity should have query stored", queryEntity.getQuery(), queryString);
        assertEquals("Resource result id and Picsure result id should match in case of no resource result id", queryEntity.getResourceResultId(), queryEntity.getUuid().toString());

    }

    @Test
    public void testQueryStatus(){
        /* SET UP MOCKS */

        //Setup a pre-existing query Entity
        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.PENDING);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);

        when(mockResource.getUuid()).thenReturn(resourceId);

        QueryRequest statusRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        statusRequest.setResourceCredentials(clientCredentials);
        try {
            QueryStatus result = queryService.queryStatus(null, statusRequest);
            fail("Missing queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_QUERY_ID + "'", ProtocolException.MISSING_QUERY_ID, e.getContent().toString());
        }

        //Nonexistent queryId
        try {
            QueryStatus result = queryService.queryStatus(UUID.randomUUID(), statusRequest);
            fail("Nonexistent queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ProtocolException.QUERY_NOT_FOUND + "'", e.getContent().toString().contains(ProtocolException.QUERY_NOT_FOUND));
        }

        //Test missing target URL
        try {
            QueryStatus result = queryService.queryStatus(queryId, statusRequest);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_TARGET_URL + "'", ApplicationException.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        try {
            QueryStatus result = queryService.queryStatus(queryId, statusRequest);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //Let's say the status has changed
        results.setStatus(PicSureStatus.AVAILABLE);
        when(webClient.queryStatus(any(),any(), any())).thenReturn(results);


        //This one should work
        QueryStatus result = queryService.queryStatus(queryId, statusRequest);
        //These fields are set by the method
        assertNotNull("Result should not be null", result);
        assertEquals("Picsure ResultId should match", queryId, result.getPicsureResultId());
        assertEquals("Resource Id should match", resourceId, result.getResourceID());
        assertNotNull("Start time should not be null", result.getStartTime());

        //Make sure info was saved to the query entity
        assertEquals("Query status should have been updated", PicSureStatus.AVAILABLE, queryEntity.getStatus());
    }

    @Test
    public void testQueryResult(){
        //Setup a pre-existing query Entity
        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.AVAILABLE);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);
        Response resp = mock(Response.class);
        when(webClient.queryResult(any(),any(), any())).thenReturn(resp);

        QueryRequest resultRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        resultRequest.setResourceCredentials(clientCredentials);
        try {
            Response result = queryService.queryResult(null, resultRequest);
            fail("Missing queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_QUERY_ID + "'", ProtocolException.MISSING_QUERY_ID, e.getContent().toString());
        }

        //Nonexistent queryId
        try {
            Response result = queryService.queryResult(UUID.randomUUID(), resultRequest);
            fail("Nonexistent queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ProtocolException.QUERY_NOT_FOUND + "'", e.getContent().toString().contains(ProtocolException.QUERY_NOT_FOUND));
        }

        //Test missing target URL
        try {
            Response result = queryService.queryResult(queryId, resultRequest);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_TARGET_URL + "'", ApplicationException.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        try {
            Response result = queryService.queryResult(queryId, resultRequest);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //This one should work
        Response result = queryService.queryResult(queryId, resultRequest);
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testQuerySync() {

        /* SET UP MOCKS */
        //Add needed data to results that are returned
        results.setResourceID(resourceId);
        results.setStatus(PicSureStatus.AVAILABLE);
        results.setStartTime(new Date().getTime());

        //Return mocks when needed
        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);
        Response resp = mock(Response.class);
        when(webClient.querySync(any(),any())).thenReturn(resp);

        //Mock persisting the queryentity, so that it has an ID and we can test that the correct information is stored in it
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Query query = invocation.getArgument(0);
                query.setUuid(queryId);
                queryEntity = query;
                return null;
            }
        }).when(queryRepo).persist(any(Query.class));

        /* END SET UP */

        //Test missing query data
        try {
            Response result = queryService.querySync(null);
            fail("Missing query request info should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_DATA + "'", ProtocolException.MISSING_DATA, e.getContent().toString());
        }

        QueryRequest dataQueryRequest = new QueryRequest();
        //At this level we don't check the credentials themselves, just that the map exists
        Map<String, String> clientCredentials = new HashMap<String, String>();
        dataQueryRequest.setResourceCredentials(clientCredentials);

        //Test missing resourceId
        dataQueryRequest.setQuery(queryString);
        try {
            Response result = queryService.querySync(dataQueryRequest);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'", ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString());
        }

        //Test nonexistent resourceId
        dataQueryRequest.setResourceUUID(UUID.randomUUID());
        try {
            Response result = queryService.querySync(dataQueryRequest);
            fail("Nonexistent resourceId should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ApplicationException.MISSING_RESOURCE + "'", e.getContent().toString().contains(ApplicationException.MISSING_RESOURCE));
        }

        //Test missing targetURL
        dataQueryRequest.setResourceUUID(resourceId);
        try {
            Response result = queryService.querySync(dataQueryRequest);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_TARGET_URL + "'", ApplicationException.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        dataQueryRequest.setResourceUUID(resourceId);
        try {
            Response result = queryService.querySync(dataQueryRequest);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //Test correct request
        dataQueryRequest.setResourceUUID(resourceId);
        Response result = queryService.querySync(dataQueryRequest);
        assertNotNull("Result should not be null", result.getStatus());

        //Make sure the query is persisted
        assertNotNull("Query Entity should have been persisted", queryEntity);
        assertEquals("QueryEntity should be linked to resource", queryEntity.getResource(), mockResource);
        assertEquals("Query Entity should have query stored", queryEntity.getQuery(), queryString);
        assertEquals("Resource result id and Picsure result id should match in case of no resource result id", queryEntity.getResourceResultId(), queryEntity.getUuid().toString());

    }
}
