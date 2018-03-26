package edu.harvard.hms.dbmi.avillach.irct;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.junit.Test;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;
import static org.junit.Assert.*;


import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class IRCTResourceIT extends BaseIT {

	private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
	private final static ObjectMapper json = new ObjectMapper();
	private final static String queryString = "\n" +
			"{\n" +
			"    \"select\": [\n" +
			"        {\n" +
			"            \"alias\": \"gender\", \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male\", \"dataType\":\"STRING\"}\n" +
			"        },\n" +
			"        {\n" +
			"            \"alias\": \"gender\", \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/SEX/female\", \"dataType\":\"STRING\"}\n" +
			"        },\n" +
			"        {\n" +
			"            \"alias\": \"age\",    \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/AGE\", \"dataType\":\"STRING\"}\n" +
			"        }\n" +
			"    ],\n" +
			"    \"where\": [\n" +
			"        {\n" +
			"            \"predicate\": \"CONTAINS\",\n" +
			"            \"field\": {\n" +
			"                \"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male/\",\n" +
			"                \"dataType\": \"STRING\"\n" +
			"            },\n" +
			"            \"fields\": {\n" +
			"                \"ENOUNTER\": \"YES\"\n" +
			"            }\n" +
			"        }\n" +
			"    ]\n" +
			"}";

	@Test
	public void testStatus() throws UnsupportedOperationException, IOException {
		HttpResponse response = retrieveGetResponse(endpointUrl+"/v1.4/status", null);
		assertEquals(200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testInfo() throws UnsupportedOperationException, IOException {
		//Should throw an error if credentials missing or wrong
		Map<String, String> credentials = new HashMap<String, String>();
		String body = json.writeValueAsString(credentials);
		HttpResponse response = retrievePostResponse(endpointUrl+"/v1.4/info", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = json.writeValueAsString(credentials);
		response = retrievePostResponse(endpointUrl+"/v1.4/info", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//This should work
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		body = json.writeValueAsString(credentials);
		response = retrievePostResponse(endpointUrl+"/v1.4/info", null, body);
        assertEquals(200, response.getStatusLine().getStatusCode());
		String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
	}

	@Test
	public void testSearch() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		Map<String, String> credentials = new HashMap<String, String>();
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery("%antibody%");
		String body = json.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(endpointUrl+"/v1.4/search", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		queryRequest.setResourceCredentials(credentials);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(endpointUrl+"/v1.4/search", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//Should throw an error if missing query string
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(endpointUrl+"/v1.4/search", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//This should work
		queryRequest.setQuery("%antibody%");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(endpointUrl+"/v1.4/search", null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testQuery() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		Map<String, String> credentials = new HashMap<String, String>();
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(queryString);
		String body = json.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(endpointUrl+"/v1.4/query", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		queryRequest.setResourceCredentials(credentials);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(endpointUrl+"/v1.4/query", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//Should throw an error if missing query string
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(endpointUrl+"/v1.4/query", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//This should work
		queryRequest.setQuery(queryString);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(endpointUrl+"/v1.4/query", null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
		QueryResults result = readObjectFromResponse(response, QueryResults.class);
		assertNotNull("Result should not be null", result);
		//Make sure all necessary fields are present
//		assertNotNull("Status should not be null",result.getStatus());
		assertNotNull("ResourceResultId should not be null",result.getResourceResultId());
	}

	@Test
	public void testQueryResult() throws UnsupportedOperationException, IOException {
        //Need to create a query to get an id for testing
        //TODO There must be a better way
        QueryRequest queryRequest = new QueryRequest();
        Map<String, String> credentials = new HashMap<String, String>();
        queryRequest.setResourceCredentials(credentials);
        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        queryRequest.setResourceCredentials(credentials);
        queryRequest.setQuery(queryString);
        String body = json.writeValueAsString(queryRequest);
       // HttpResponse response = retrievePostResponse(endpointUrl+"/v1.4/query", null, body);
        //assertEquals(200, response.getStatusLine().getStatusCode());
        //QueryResults result = readObjectFromResponse(response, QueryResults.class);
//        String testId = result.getResourceResultId();
		String testId = "230984";

        credentials = new HashMap<String, String>();
        body = json.writeValueAsString(credentials);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(endpointUrl+"/v1.4/query/"+testId+"/result", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = json.writeValueAsString(credentials);
		response = retrievePostResponse(endpointUrl+"/v1.4/query/"+testId+"/result", null, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//This should work - or will throw a 404 because the UUID is fake
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		body = json.writeValueAsString(credentials);
		response = retrievePostResponse(endpointUrl+"/v1.4/query/"+testId+"/result", null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
		QueryResults result = readObjectFromResponse(response, QueryResults.class);
		assertNotNull("Result should not be null", result);
		//Make sure all necessary fields are present
//		assertNotNull("ResultMetadata should not be null",result.getResultMetadata());
		assertNotNull("Results should not be null",result.getResults());
//		assertNotNull("Status should not be null",result.getStatus());
		assertEquals("Resource id should match that requested",result.getResourceResultId(), testId);
	}

	@Test
	public void testQueryStatus() throws UnsupportedOperationException, IOException {
	    //Need to create a query to get an id for testing
        //TODO There must be a better way
        QueryRequest queryRequest = new QueryRequest();
        Map<String, String> credentials = new HashMap<String, String>();
        queryRequest.setResourceCredentials(credentials);
        credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        queryRequest.setResourceCredentials(credentials);
        queryRequest.setQuery(queryString);
        String body = json.writeValueAsString(queryRequest);
        HttpResponse response = retrievePostResponse(endpointUrl+"/v1.4/query", null, body);
        assertEquals(200, response.getStatusLine().getStatusCode());
        QueryResults result = readObjectFromResponse(response, QueryResults.class);
		String testId = result.getResourceResultId();

        credentials = new HashMap<String, String>();
        body = json.writeValueAsString(credentials);

        //Should throw an error if credentials missing or wrong
		response = retrievePostResponse(endpointUrl+"/v1.4/query/"+testId+"/status", null, body);
		assertEquals("Missing credentials should return a 500", 500, response.getStatusLine().getStatusCode());

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = json.writeValueAsString(credentials);
		response = retrievePostResponse(endpointUrl+"/v1.4/query/"+testId+"/status", null, body);
		assertEquals("Incorrect token should return a 500",500, response.getStatusLine().getStatusCode());

		//This should work - or will throw a 404 because the UUID is fake
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		body = json.writeValueAsString(credentials);
		response = retrievePostResponse(endpointUrl+"/v1.4/query/"+testId+"/status", null, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
		QueryStatus queryStatus = readObjectFromResponse(response, QueryStatus.class);
		assertNotNull("Result should not be null", result);
		//Make sure all necessary fields are present
        //TODO These are all set to 0.  When you put real values in check to make sure they're not 0
		assertNotNull("Duration should not be null",queryStatus.getDuration());
		assertNotNull("Expiration should not be null",queryStatus.getExpiration());
		assertNotNull("ResourceStatus should not be null",queryStatus.getResourceStatus());
		assertNotNull("Status should not be null",queryStatus.getStatus());
	}
}
