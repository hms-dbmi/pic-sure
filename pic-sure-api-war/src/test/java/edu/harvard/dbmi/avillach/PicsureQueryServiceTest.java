package edu.harvard.dbmi.avillach;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.*;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

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

@RunWith(MockitoJUnitRunner.class)
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
	private HeaderContext headerContext = mock(HeaderContext.class);

	@Before
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
			QueryStatus result = queryService.query(null);
			fail("Missing query request info should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertEquals("Error message should say '" + ProtocolException.MISSING_DATA + "'",
					ProtocolException.MISSING_DATA, e.getContent().toString());
		}
	}

	@Test
	public void testQueryMissingResourceId() {

		QueryRequest dataQueryRequest = new QueryRequest();
		// At this level we don't check the credentials themselves, just that the map
		// exists
		Map<String, String> clientCredentials = new HashMap<String, String>();
		dataQueryRequest.setResourceCredentials(clientCredentials);

		// Test missing resourceId

		dataQueryRequest.setQuery(queryString);
		try {
			QueryStatus result = queryService.query(dataQueryRequest);
			fail("Missing resourceId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertEquals("Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'",
					ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString());
		}

	}

	@Test
	public void testQueryInvalidResourceId() {

		QueryRequest dataQueryRequest = new QueryRequest();
		// At this level we don't check the credentials themselves, just that the map
		// exists
		Map<String, String> clientCredentials = new HashMap<String, String>();
		dataQueryRequest.setResourceCredentials(clientCredentials);

		// Test nonexistent resourceId
		dataQueryRequest.setResourceUUID(UUID.randomUUID());
		try {
			QueryStatus result = queryService.query(dataQueryRequest);
			fail("Nonexistent resourceId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertTrue("Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'",
					e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND));
		}

	}

	@Test
	public void testQueryValidRequest() {
		QueryRequest dataQueryRequest = new QueryRequest();
		// At this level we don't check the credentials themselves, just that the map exists
		Map<String, String> clientCredentials = new HashMap<String, String>();
		dataQueryRequest.setResourceCredentials(clientCredentials);
		dataQueryRequest.setResourceUUID(resourceId);
		dataQueryRequest.setQuery(queryString);
		
		QueryStatus result = queryService.query(dataQueryRequest);
		assertNotNull("Status should not be null", result.getStatus());
		assertNotNull("Resource result id should not be null", result.getResourceResultId());
		assertNotNull("Picsure result id should not be null", result.getPicsureResultId());
		// Since there was no resource result id, it should be the same as the picsure
		// result id
		assertEquals("Resource result id and Picsure result id should match in case of no resource result id",
				result.getResourceResultId(), result.getPicsureResultId().toString());

		// Make sure the query is persisted
		assertNotNull("Query Entity should have been persisted", queryEntity);
		assertEquals("QueryEntity should be linked to resource", queryEntity.getResource(), mockResource);

		assertTrue("Query Entity should have query stored", queryEntity.getQuery().contains(queryString));
		assertEquals("Resource result id and Picsure result id should match in case of no resource result id",
				queryEntity.getResourceResultId(), queryEntity.getUuid().toString());

	}

	@Test
	public void testQueryStatusNoId() {

		QueryRequest statusRequest = new QueryRequest();
		Map<String, String> clientCredentials = new HashMap<String, String>();
		statusRequest.setResourceCredentials(clientCredentials);
		try {
			QueryStatus result = queryService.queryStatus(null, statusRequest);
			fail("Missing queryId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertEquals("Error message should say '" + ProtocolException.MISSING_QUERY_ID + "'",
					ProtocolException.MISSING_QUERY_ID, e.getContent().toString());
		}

	}

	@Test
	public void testQueryStatusInvalidId() {

		QueryRequest statusRequest = new QueryRequest();
		Map<String, String> clientCredentials = new HashMap<String, String>();
		statusRequest.setResourceCredentials(clientCredentials);

		// Nonexistent queryId
		try {
			QueryStatus result = queryService.queryStatus(UUID.randomUUID(), statusRequest);
			fail("Nonexistent queryId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertTrue("Error message should say '" + ProtocolException.QUERY_NOT_FOUND + "'",
					e.getContent().toString().contains(ProtocolException.QUERY_NOT_FOUND));
		}

	}

	@Test
	public void testQueryStatusValid() {

		QueryRequest statusRequest = new QueryRequest();
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
		
		results.setStatus(PicSureStatus.AVAILABLE);  //this should update the DB entity status
		results.setStartTime(new Date().getTime());
		when(webClient.queryStatus(any(), any(), any())).thenReturn(results);

		// This one should work
		QueryStatus result = queryService.queryStatus(queryId, statusRequest);
		// These fields are set by the method
		assertNotNull("Result should not be null", result);
		assertEquals("Picsure ResultId should match", queryId, result.getPicsureResultId());
		assertEquals("Resource Id should match", resourceId, result.getResourceID());
		assertNotNull("Start time should not be null", result.getStartTime());

		// Make sure info was saved to the query entity
		assertEquals("Query status should have been updated", PicSureStatus.AVAILABLE, queryEntity.getStatus());
	}

	@Test
	public void testQueryResultNoId() {


		QueryRequest resultRequest = new QueryRequest();
		Map<String, String> clientCredentials = new HashMap<String, String>();
		resultRequest.setResourceCredentials(clientCredentials);
		try {
			Response result = queryService.queryResult(null, resultRequest);
			fail("Missing queryId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertEquals("Error message should say '" + ProtocolException.MISSING_QUERY_ID + "'",
					ProtocolException.MISSING_QUERY_ID, e.getContent().toString());
		}

	}

	@Test
	public void testQueryResultInvalidId() {


		QueryRequest resultRequest = new QueryRequest();
		Map<String, String> clientCredentials = new HashMap<String, String>();
		resultRequest.setResourceCredentials(clientCredentials);
		try {
			Response result = queryService.queryResult(UUID.randomUUID(), resultRequest);
			fail("Nonexistent queryId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertTrue("Error message should say '" + ProtocolException.QUERY_NOT_FOUND + "'",
					e.getContent().toString().contains(ProtocolException.QUERY_NOT_FOUND));
		}
		
		
	}

	@Test
	public void testQueryResultValid() {


		QueryRequest resultRequest = new QueryRequest();
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
		Response result = queryService.queryResult(queryId, resultRequest);
		assertNotNull("Result should not be null", result);
	}

	@Test
	public void testQuerySyncNoQuery() {

		// Test missing query data
		try {
			Response result = queryService.querySync(null);
			fail("Missing query request info should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertEquals("Error message should say '" + ProtocolException.MISSING_DATA + "'",
					ProtocolException.MISSING_DATA, e.getContent().toString());
		}

	}

	@Test
	public void testQuerySyncNoResourceId() {

		QueryRequest dataQueryRequest = new QueryRequest();
		// At this level we don't check the credentials themselves, just that the map
		// exists
		Map<String, String> clientCredentials = new HashMap<String, String>();
		dataQueryRequest.setResourceCredentials(clientCredentials);

		// Test missing resourceId
		dataQueryRequest.setQuery(queryString);
		try {
			Response result = queryService.querySync(dataQueryRequest);
			fail("Missing resourceId should throw an error");
		} catch (ProtocolException e) {
			assertNotNull(e.getContent());
			assertEquals("Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'",
					ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString());
		}

	}

	@Test
	public void testQuerySyncInvalidResourceId() {

		QueryRequest dataQueryRequest = new QueryRequest();
		// At this level we don't check the credentials themselves, just that the map
		// exists
		Map<String, String> clientCredentials = new HashMap<String, String>();
		dataQueryRequest.setResourceCredentials(clientCredentials);
		// Test nonexistent resourceId
		dataQueryRequest.setResourceUUID(UUID.randomUUID());
		try {
			Response result = queryService.querySync(dataQueryRequest);
			fail("Nonexistent resourceId should throw an error");
		} catch (ApplicationException e) {
			assertNotNull(e.getContent());
			assertTrue("Error message should say '" + ApplicationException.MISSING_RESOURCE + "'",
					e.getContent().toString().contains(ApplicationException.MISSING_RESOURCE));
		}

	}

	@Test
	public void testQuerySyncValidNoResponseId() {

		QueryRequest dataQueryRequest = new QueryRequest();
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
		when(webClient.querySync(any(), any())).thenReturn(resp);

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
		Response result = queryService.querySync(dataQueryRequest);
		assertNotNull("Result should not be null", result.getStatus());

		// Make sure the query is persisted
		assertNotNull("Query Entity should have been persisted", queryEntity);
		assertEquals("QueryEntity should be linked to resource", queryEntity.getResource(), mockResource);

		assertTrue("Query Entity should have query stored", queryEntity.getQuery().contains(queryString));
		assertEquals("Resource result id and Picsure result id should match in case of no resource result id",
				queryId.toString(), queryEntity.getResourceResultId());

	}
	
	@Test
	public void testQuerySyncValidWithResponseId() {

		QueryRequest dataQueryRequest = new QueryRequest();
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
		
		MultivaluedMap<String, Object> headerMap= new MultivaluedHashMap<String, Object>();
		headerMap.add(ResourceWebClient.QUERY_METADATA_FIELD, resultId);
		when(resp.getHeaders()).thenReturn(headerMap);
		when(webClient.querySync(any(), any())).thenReturn(resp);

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
		Response result = queryService.querySync(dataQueryRequest);
		assertNotNull("Result should not be null", result.getStatus());

		// Make sure the query is persisted
		assertNotNull("Query Entity should have been persisted", queryEntity);
		assertEquals("QueryEntity should be linked to resource", queryEntity.getResource(), mockResource);

		assertTrue("Query Entity should have query stored", queryEntity.getQuery().contains(queryString));
		assertEquals("Resource result id should match returned header",
				resultId, queryEntity.getResourceResultId());

	}
}
