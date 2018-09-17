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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
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
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class PicsureQueryServiceTest extends BaseServiceTest {

    private UUID resourceId = UUID.randomUUID();
    private String token = "tokenDoesntMatterForTestIDontThink";
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

    @Before
    public void setUp() {
        //TODO Reorganize this so things are only setup when needed.
        //Create a QueryStatus to return
        results.setResourceID(resourceId);
        results.setStatus(PicSureStatus.PENDING);
        results.setStartTime(new Date().getTime());

        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);
        when(resourceRepo.getById(not(ArgumentMatchers.same(resourceId)))).thenReturn(null);
        when(mockResource.getUuid()).thenReturn(resourceId);
        when(webClient.query(any(), any())).thenReturn(results);

        //Mock persisting the queryentity, so that it has an ID and we can test that the correct information is stored in it
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                Query query = invocation.getArgument(0);
                query.setUuid(queryId);
                queryEntity = query;
                return null;
            }
        }).when(queryRepo).persist(any(Query.class));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);


    }

    @Test
    public void testQuery() throws Exception {

        //Test missing query data
        try {
            QueryStatus result = queryService.query(null);
            fail("Missing query request info should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_DATA + "'", PicsureQueryService.MISSING_DATA, e.getContent().toString());
        }

        QueryRequest dataQueryRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        dataQueryRequest.setResourceCredentials(clientCredentials);

        //Test missing resourceId
        dataQueryRequest.setQuery(queryString);
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_RESOURCE_ID + "'", PicsureQueryService.MISSING_RESOURCE_ID, e.getContent().toString());
        }

        //Test nonexistent resourceId
        dataQueryRequest.setResourceUUID(UUID.randomUUID());
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Nonexistent resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + PicsureQueryService.RESOURCE_NOT_FOUND + "'", e.getContent().toString().contains(PicsureQueryService.RESOURCE_NOT_FOUND));
        }

        //Test missing targetURL
        dataQueryRequest.setResourceUUID(resourceId);
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_TARGET_URL + "'", PicsureQueryService.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        dataQueryRequest.setResourceUUID(resourceId);
        try {
            QueryStatus result = queryService.query(dataQueryRequest);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_RESOURCE_PATH + "'", PicsureQueryService.MISSING_RESOURCE_PATH, e.getContent().toString());
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
        //Setup a pre-existing query Entity
        queryEntity = new Query();
        queryEntity.setUuid(queryId);
        queryEntity.setResourceResultId(queryId.toString());
        queryEntity.setResource(mockResource);
        queryEntity.setStatus(PicSureStatus.PENDING);
        queryEntity.setQuery(queryString);
        queryEntity.setStartTime(new java.sql.Date(results.getStartTime()));
        when(queryRepo.getById(queryId)).thenReturn(queryEntity);

        //TODO: Do we check client credentials at this level?
        /*try {
            QueryStatus result = queryService.queryStatus(queryId, null);
            fail("Missing resource credentials should throw an error");
        } catch (Exception e){
        }*/

        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        try {
            QueryStatus result = queryService.queryStatus(null, clientCredentials);
            fail("Missing queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_QUERY_ID + "'", PicsureQueryService.MISSING_QUERY_ID, e.getContent().toString());
        }

        //Nonexistent queryId
        try {
            QueryStatus result = queryService.queryStatus(UUID.randomUUID(), clientCredentials);
            fail("Nonexistent queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + PicsureQueryService.QUERY_NOT_FOUND + "'", e.getContent().toString().contains(PicsureQueryService.QUERY_NOT_FOUND));
        }

        //Test missing target URL
        try {
            QueryStatus result = queryService.queryStatus(queryId, clientCredentials);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_TARGET_URL + "'", PicsureQueryService.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        try {
            QueryStatus result = queryService.queryStatus(queryId, clientCredentials);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_RESOURCE_PATH + "'", PicsureQueryService.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //Let's say the status has changed
        results.setStatus(PicSureStatus.AVAILABLE);
        when(webClient.queryStatus(any(),any(), any())).thenReturn(results);


        //This one should work
        QueryStatus result = queryService.queryStatus(queryId, clientCredentials);
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

        /*try {
            Response result = queryService.queryResult(queryId, null);
            fail("Missing resource credentials should throw an error");
        } catch (Exception e){
        }*/

        Map<String, String> clientCredentials = new HashMap<String, String>();
//        clientCredentials.put(IRCT_BEARER_TOKEN_KEY, token);
        try {
            Response result = queryService.queryResult(null, clientCredentials);
            fail("Missing queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_QUERY_ID + "'", PicsureQueryService.MISSING_QUERY_ID, e.getContent().toString());
        }

        //Nonexistent queryId
        try {
            Response result = queryService.queryResult(UUID.randomUUID(), clientCredentials);
            fail("Nonexistent queryId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + PicsureQueryService.QUERY_NOT_FOUND + "'", e.getContent().toString().contains(PicsureQueryService.QUERY_NOT_FOUND));
        }

        //Test missing target URL
        try {
            Response result = queryService.queryResult(queryId, clientCredentials);
            fail("Missing targetURL should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_TARGET_URL + "'", PicsureQueryService.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        //Test missing resourceRS Path
        try {
            Response result = queryService.queryResult(queryId, clientCredentials);
            fail("Missing resourceRS path should throw an error");
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + PicsureQueryService.MISSING_RESOURCE_PATH + "'", PicsureQueryService.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //This one should work
        Response result = queryService.queryResult(queryId, clientCredentials);
        assertNotNull("Result should not be null", result);
    }
}
