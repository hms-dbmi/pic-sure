package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.*;
import static org.junit.Assert.*;

public class HSAPIResourceIT extends BaseIT {

	private final String targetURL = "http://localhost:8079";
	private Header[] headers;

	@Test
	public void testStatus() throws UnsupportedOperationException {
		HttpResponse response = retrieveGetResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/status"), headers);
		assertEquals("Status should return a 200", 200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testInfo() throws UnsupportedOperationException, IOException {
		QueryRequest request = new GeneralQueryRequest();

		String body = objectMapper.writeValueAsString(request);
		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/info"), headers, body);
        assertEquals("Request should return a 200",200, response.getStatusLine().getStatusCode());
        JsonNode responseNode = objectMapper.readTree(response.getEntity().getContent());
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
		QueryRequest queryRequest = new GeneralQueryRequest();

		String body = objectMapper.writeValueAsString(queryRequest);

		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/search"), headers, body);
		assertEquals("Search should return a 501",501, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be 'Search is not implemented for this resource'", "Search is not implemented for this resource", errorMessage);
    }

	@Test
	public void testQuery() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new GeneralQueryRequest();

		String body = objectMapper.writeValueAsString(queryRequest);

		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query"), headers, body);
		assertEquals("Search should return a 501",501, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be 'Query is not implemented in this resource.  Please use query/sync'", "Query is not implemented in this resource.  Please use query/sync", errorMessage);
	}

	//These tests will throw a 404 unless we have a valid queryId which can't really be gotten just for a test....
	/*@Test
	public void testQueryStatus() throws UnsupportedOperationException, IOException {
			}

	@Test
	public void testRequest() throws UnsupportedOperationException, IOException {
		}*/

	@Test
	public void testQuerySync() throws UnsupportedOperationException, IOException {
		Map<String, Object > resourceResponse = new HashMap<>();
		List<Map<String, String>> results = new ArrayList<>();
		Map<String, String> firstResult = new HashMap<>();
		firstResult.put("resource_id", "123abc");
		firstResult.put("url", "http://aUrl.org/path/contents/file.csv");
		results.add(firstResult);
		resourceResponse.put("results", results);

		wireMockRule.stubFor(any(urlPathMatching("/resource"))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resourceResponse))));

		wireMockRule.stubFor(any(urlPathMatching("/resource/.*/files"))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resourceResponse))));

		wireMockRule.stubFor(any(urlPathMatching("/resource/.*/files/.*"))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resourceResponse))));

		QueryRequest queryRequest = new GeneralQueryRequest();
		String body = objectMapper.writeValueAsString(queryRequest);

		//Should throw an error if missing query object
		HttpResponse response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals("Missing query object should return a 500",500, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorMessage = responseMessage.get("message").asText();
		System.out.println("Response message is: " + responseMessage);
		assertTrue("Error message should be " + ProtocolException.MISSING_DATA, errorMessage.contains(ProtocolException.MISSING_DATA));

		//Should throw an error if missing targetURL
		Map<String, String> queryNode = new HashMap<>();
		queryRequest.setQuery(queryNode);
//		body = objectMapper.writeValueAsString(queryRequest);
//		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
//		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
//		responseMessage = objectMapper.readTree(response.getEntity().getContent());
//		assertNotNull("Response message should not be null", responseMessage);
//		errorMessage = responseMessage.get("message").asText();
//		assertTrue("Error message should be " + ApplicationException.MISSING_TARGET_URL, errorMessage.contains(ApplicationException.MISSING_TARGET_URL));

		//Should throw error if no 'entity' included
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals("Missing entity should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be 'Entity required'", errorMessage.contains("Entity required"));

		queryNode.put("entity", "nonentity");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals("Incorrect entity should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		//TODO Should we do something different instead?
		assertTrue("Error message should be '404 NOT FOUND'", errorMessage.toUpperCase().contains("404 NOT FOUND"));

		//Ok this one should work
		queryNode.put("entity", "resource");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals(200, response.getStatusLine().getStatusCode());

		//Need to get an id to use
		JsonNode result = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Results should not be null", result.get("results"));
		String id = result.get("results").get(0).get("resource_id").asText();

		//This skips over the required id
		queryNode.put("subentity", "files");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals("Incorrect entity should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be 'Cannot have subentity without an id'", errorMessage.contains("Cannot have subentity without an id"));

		queryNode.put("id", id);
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals(200, response.getStatusLine().getStatusCode());

		//Need to get a pathname to use
		result = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Results should not be null", result.get("results"));

		String pathname = result.get("results").get(0).get("url").asText();
		pathname = pathname.substring(pathname.lastIndexOf("/"));
		queryNode.put("pathname", pathname);
		queryNode.put("subentity", "files");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals(200, response.getStatusLine().getStatusCode());

		//Should fail with a page where it isn't allowed
		queryNode.put("page", "2");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals(500, response.getStatusLine().getStatusCode());

		//But should work with a page for this one
		queryNode.remove("pathname");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(composeURL(hsapiEndpointUrl,"pic-sure/hsapi/query/sync"), headers, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
        result = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", result.get("results"));

	}
}
