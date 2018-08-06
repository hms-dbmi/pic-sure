package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;
import static org.junit.Assert.*;

public class IRCTResourceIT extends BaseIT {

	private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
	private final static String queryString = "{" +
			"    \"select\": [" +
			"        {" +
			"            \"alias\": \"gender\", \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male\", \"dataType\":\"STRING\"}" +
			"        }," +
			"        {" +
			"            \"alias\": \"gender\", \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/SEX/female\", \"dataType\":\"STRING\"}" +
			"        }," +
			"        {" +
			"            \"alias\": \"age\",    \"field\": {\"pui\": \"/nhanes/Demo/demographics/demographics/AGE\", \"dataType\":\"STRING\"}" +
			"        }" +
			"    ]," +
			"    \"where\": [" +
			"        {" +
			"            \"predicate\": \"CONTAINS\"," +
			"            \"field\": {" +
			"                \"pui\": \"/nhanes/Demo/demographics/demographics/SEX/male/\"," +
			"                \"dataType\": \"STRING\"" +
			"            }," +
			"            \"fields\": {" +
			"                \"ENOUNTER\": \"YES\"" +
			"            }" +
			"        }" +
			"    ]" +
			"}";
    //This is a previously created query id, uncertain if this is the best way to go
	private String testQueryResultId = "231066";
	private final String targetURL = "https://nhanes.hms.harvard.edu/rest/v1/";

	//TODO: May change the way all errors are dealt with

	@Test
	public void testStatus() throws UnsupportedOperationException, IOException {
		System.out.println(irctEndpointUrl);
		HttpResponse response = retrieveGetResponse(irctEndpointUrl+"pic-sure/v1.4/status", null);
		assertEquals("Status should return a 200", 200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testInfo() throws UnsupportedOperationException, IOException {
		QueryRequest request = new QueryRequest();
		request.setTargetURL(targetURL);

		//Should throw an error if credentials missing or wrong
		Map<String, String> credentials = new HashMap<String, String>();
		request.setResourceCredentials(credentials);
		String body = json.writeValueAsString(request);
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", null, body);
		assertEquals("Missing credentials should return a 401",401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = json.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", null, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		request.setTargetURL(null);
		body = json.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", null, body);
		assertEquals("Missing target URL should return a 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);


		//This should work
		request.setTargetURL(targetURL);
		body = json.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", null, body);
        assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
        JsonNode responseNode = json.readTree(response.getEntity().getContent());
        assertNotNull("Response should not be null", responseNode);
        assertNotNull("Response should have a name", responseNode.get("name"));
        assertNotNull("Response should have data in queryFormats", responseNode.get("queryFormats"));
	}

	@Test
	public void testSearch() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		Map<String, String> credentials = new HashMap<String, String>();
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery("%antibody%");
		queryRequest.setTargetURL(targetURL);

		String body = json.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", null, body);
		assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		queryRequest.setResourceCredentials(credentials);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", null, body);
		assertEquals("Incorrect token should return a 401", 401, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
/*		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));
*/

		//Should throw an error if missing query string
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", null, body);
		assertEquals("Missing query string should return a 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be " + IRCTResourceRS.MISSING_REQUEST_DATA_MESSAGE, IRCTResourceRS.MISSING_REQUEST_DATA_MESSAGE, errorMessage);


		queryRequest.setQuery("%antibody%");
		queryRequest.setTargetURL(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", null, body);
		assertEquals("Missing target URL should return a 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be " + IRCTResourceRS.MISSING_TARGET_URL, IRCTResourceRS.MISSING_TARGET_URL, errorMessage);

		//This should work
		queryRequest.setTargetURL(targetURL);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", null, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
        JsonNode responseNode = json.readTree(response.getEntity().getContent());
        assertNotNull("Result should not be null", responseNode);
        assertNotNull("Search results should not be null", responseNode.get("results"));
		assertFalse("Search results should not be empty", responseNode.get("results").size() == 0);
        assertEquals("Searchquery should match input query", "%antibody%", responseNode.get("searchQuery").asText());

        //Valid request with no results should return an empty result
		queryRequest.setQuery("thisShouldFindNothing");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", null, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
		responseNode = json.readTree(response.getEntity().getContent());
		assertNotNull("Result should not be null", responseNode);
		assertNotNull("Search results should not be null", responseNode.get("results"));
		assertTrue("Search results should be empty", responseNode.get("results").size() == 0);
		assertEquals("Searchquery should match input query", "thisShouldFindNothing", responseNode.get("searchQuery").asText());
    }

	@Test
	public void testQuery() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		Map<String, String> credentials = new HashMap<String, String>();
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(queryString);
		queryRequest.setTargetURL(targetURL);
		String body = json.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
		assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		queryRequest.setResourceCredentials(credentials);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

		//Should throw an error if missing query string
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
		assertEquals("Missing query string should return a 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_REQUEST_DATA_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_REQUEST_DATA_MESSAGE));


		//Try a poorly worded queryString
		queryRequest.setQuery("poorlyWordedQueryString");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
		assertEquals("Incorrectly formatted string should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "ri_error", errorType);
		/*errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));
*/

		queryRequest.setQuery(queryString);
		queryRequest.setTargetURL(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

		queryRequest.setTargetURL(targetURL);
		JsonNode jsonNode = json.readTree(queryString);

		//Request can be an object that also requests the format
		ObjectNode queryNode = json.createObjectNode();
        queryNode.put("queryString", jsonNode);
		queryRequest.setQuery(queryNode);

		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
		QueryStatus result = readObjectFromResponse(response, QueryStatus.class);
		assertNotNull("Result should not be null", result);
		//Make sure all necessary fields are present
		assertNotNull("Status should not be null",result.getStatus());
		assertNotNull("ResourceResultId should not be null",result.getResourceResultId());

        //Or else just a query
        queryRequest.setQuery(jsonNode);
        body = json.writeValueAsString(queryRequest);
        response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", null, body);
        assertEquals(200, response.getStatusLine().getStatusCode());
        result = readObjectFromResponse(response, QueryStatus.class);
        assertNotNull("Result should not be null", result);
        //Make sure all necessary fields are present
        assertNotNull("Status should not be null",result.getStatus());
        assertNotNull("ResourceResultId should not be null",result.getResourceResultId());
    }

	@Test
	public void testQueryResult() throws UnsupportedOperationException, IOException {
		QueryRequest queryRequest = new QueryRequest();
		queryRequest.setTargetURL(targetURL);
	    Map<String, String> credentials = new HashMap<String, String>();
	    queryRequest.setResourceCredentials(credentials);
        String body = json.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", null, body);
		assertEquals("Missing credentials should return a 401",401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", null, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        body = json.writeValueAsString(queryRequest);

        //TODO This is just returning what IRCT returns - do we need to test it?
		//False query id should return a failure message
        response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/111/result", null, body);
		assertEquals("Should return a 500",500, response.getStatusLine().getStatusCode());

		queryRequest.setTargetURL(null);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", null, body);
		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

        //This should work
		queryRequest.setTargetURL(targetURL);
		body = json.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", null, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
		String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
		assertFalse("Response content should not be empty", responseBody.isEmpty());
	}

	@Test
	public void testQueryStatus() throws UnsupportedOperationException, IOException {
		QueryRequest request = new QueryRequest();
		request.setTargetURL(targetURL);
        Map<String, String>credentials = new HashMap<String, String>();
        request.setResourceCredentials(credentials);
        String body = json.writeValueAsString(request);

        //Should throw an error if credentials missing or wrong
        HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", null, body);
		assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		request.setResourceCredentials(credentials);
		body = json.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", null, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
/*		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));
*/

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		request.setResourceCredentials(credentials);
		request.setTargetURL(null);
		body = json.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", null, body);
		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

		//This should work
		request.setTargetURL(targetURL);
		body = json.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", null, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
		QueryStatus queryStatus = readObjectFromResponse(response, QueryStatus.class);
		assertNotNull("Result should not be null", queryStatus);
		//Make sure all necessary fields are present
        //TODO The numerical values are set in PicSureRS layer, may not apply here
		assertNotNull("Duration should not be null",queryStatus.getDuration());

		assertNotNull("Expiration should not be null",queryStatus.getExpiration());
		assertNotNull("ResourceStatus should not be null",queryStatus.getResourceStatus());
		assertNotNull("Status should not be null",queryStatus.getStatus());
		assertNotNull("Starttime should not be null",queryStatus.getStartTime());

		//Try a queryId that doesn't exist
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/111/status", null, body);
		//assertEquals("Nonexistent queryId should return a 500",500, response.getStatusLine().getStatusCode());

		responseMessage = json.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "ri_error", errorType);

		//TODO Do we need to check for different statuses?  If so, how?
	}
}
