package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.HSAPIResourceRS;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;
import static org.junit.Assert.*;

public class HSAPIResourceIT extends BaseIT {

	private final String targetURL = "https://beta.commonsshare.org/hsapi/";

	@Test
	public void testStatus() throws UnsupportedOperationException {
		HttpResponse response = retrieveGetResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/status"), null);
		assertEquals("Status should return a 200", 200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testInfo() throws UnsupportedOperationException, IOException {
		QueryRequest request = new QueryRequest();
		request.setTargetURL(targetURL);

		String body = json.writeValueAsString(request);
		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/info"), null, body);
        assertEquals("Request should return a 200",200, response.getStatusLine().getStatusCode());
        JsonNode responseNode = json.readTree(response.getEntity().getContent());
        assertNotNull("Response should not be null", responseNode);
        assertNotNull("Response should have a name", responseNode.get("name"));
        ArrayNode queryFormats = (ArrayNode) responseNode.get("queryFormats");
        assertNotNull("Response should have data in queryFormats", queryFormats);
		assertEquals("Response should have 3 queryFormats", 3, queryFormats.size());
        JsonNode firstFormat = queryFormats.get(0);
        assertNotNull("QueryFormat should have specifications", firstFormat.get("specification"));

	}

	@Test
	public void testSearch() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(targetURL);

		String body = json.writeValueAsString(queryRequest);

		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/search"), null, body);
		assertEquals("Search should return a 501",501, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be 'Search is not implemented for this resource'", "Search is not implemented for this resource", errorMessage);
    }

	@Test
	public void testQuery() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(targetURL);

		String body = json.writeValueAsString(queryRequest);

		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query"), null, body);
		assertEquals("Search should return a 501",501, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be 'Query is not implemented in this resource.  Please use query/sync'", "Query is not implemented in this resource.  Please use query/sync", errorMessage);
	}

	//These tests will throw a 404 unless we have a valid queryId which can't really be gotten just for a test....
	/*@Test
	public void testQueryStatus() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(targetURL);

		String body = json.writeValueAsString(queryRequest);

		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/"+resultId+"/status"), null, body);
		assertEquals("Search should return a 500",500, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be 'Query status is not implemented in this resource.  Please use query/sync'", "Query status is not implemented in this resource.  Please use query/sync", errorMessage);
	}

	@Test
	public void testRequest() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(targetURL);

		String body = json.writeValueAsString(queryRequest);

		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/"+resultId+"/result"), null, body);
		assertEquals("Search should return a 500",500, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be 'Query result is not implemented in this resource.  Please use query/sync'", "Query result is not implemented in this resource.  Please use query/sync", errorMessage);
	}*/

	@Test
	public void testQuerySync() throws UnsupportedOperationException, IOException {

		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(targetURL);
		String body = json.writeValueAsString(queryRequest);

		//Should throw an error if missing query object
		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals("Missing query object should return a 500",500, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + HSAPIResourceRS.MISSING_REQUEST_DATA_MESSAGE, errorMessage.contains(HSAPIResourceRS.MISSING_REQUEST_DATA_MESSAGE));

		//Should throw an error if missing targetURL
		Map<String, String> queryNode = new HashMap<>();
		queryRequest.setQuery(queryNode);
		queryRequest.setTargetURL(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + HSAPIResourceRS.MISSING_TARGET_URL, errorMessage.contains(HSAPIResourceRS.MISSING_TARGET_URL));

		//Should throw error if no 'entity' included
		queryRequest.setTargetURL(targetURL);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals("Missing entity should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be 'Entity required'", errorMessage.contains("Entity required"));

		queryNode.put("entity", "nonentity");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals("Incorrect entity should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		//TODO Should we do something different instead?
		assertTrue("Error message should be '404 NOT FOUND'", errorMessage.contains("404 NOT FOUND"));

		//Ok this one should work
		queryNode.put("entity", "resource");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
		//TODO What to check for
		//TODO There's no result Id here, only on the picsure rs level... that should be acceptable right?
		//Should have a resultId in the header
/*		assertTrue("Response should contain resultId", response.containsHeader("resultId"));
		Header[] headers = response.getAllHeaders();
		for (Header h : headers){
			if (h.getName().equals("resultId")){
				resultId = h.getValue();
				break;
			}
		}
		assertNotNull("Response should contain resultId", resultId);*/

		//Need to get an id to use
		JsonNode result = json.readTree(response.getEntity().getContent());
		String id = result.get("results").get(0).get("resource_id").asText();

		//TODO Hmm What else should I check*/
		//This skips over the required id
		/*queryNode.put("subentity", "file");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals("Incorrect entity should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		//TODO Should we do something different instead?
		assertTrue("Error message should be '404 NOT FOUND'", errorMessage.contains("404 NOT FOUND"));*/

		queryNode.put("id", id);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());

		//Need to get a pathname to use
	/*	result = json.readTree(response.getEntity().getContent());
		String pathname = result.get("results").get(0).get("url").asText();
		pathname = pathname.substring(pathname.lastIndexOf("/"));
		queryNode.put("pathname", pathname);
		queryNode.put("subentity", "file");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());*/

	}
}
