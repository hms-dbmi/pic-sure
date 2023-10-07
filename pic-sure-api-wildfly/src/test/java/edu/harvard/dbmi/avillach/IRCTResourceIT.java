package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static edu.harvard.dbmi.avillach.util.HttpClientUtil.*;
import static org.junit.Assert.*;

public class IRCTResourceIT extends BaseIT {

	private final static String token = "a.valid-Token";
	private final static String queryString = "{" +
			"    \"select\": [" +
			"        {" +
			"            \"alias\": \"gender\", \"field\": {\"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/SEX/male\", \"dataType\":\"STRING\"}" +
			"        }," +
			"        {" +
			"            \"alias\": \"gender\", \"field\": {\"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/SEX/female\", \"dataType\":\"STRING\"}" +
			"        }," +
			"        {" +
			"            \"alias\": \"age\",    \"field\": {\"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/AGE\", \"dataType\":\"STRING\"}" +
			"        }" +
			"    ]," +
			"    \"where\": [" +
			"        {" +
			"            \"predicate\": \"CONTAINS\"," +
			"            \"field\": {" +
			"                \"pui\": \"/i2b2-nhanes/Demo/demographics/demographics/SEX/male/\"," +
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
	private final String targetURL = "http://localhost:8079";

	@Test
	public void testStatus() throws UnsupportedOperationException, IOException {
		System.out.println(irctEndpointUrl);
		HttpResponse response = retrieveGetResponse(irctEndpointUrl+"pic-sure/v1.4/status", headers);
		assertEquals("Status should return a 200", 200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testInfo() throws UnsupportedOperationException, IOException {
		wireMockRule.stubFor(any(urlPathMatching("/resourceService/resources"))
				.withHeader("Authorization", containing("anIncorrectToken"))
				.willReturn(aResponse()
						.withStatus(401)));

		List<QueryFormat> qfs = new ArrayList<>();

		wireMockRule.stubFor(any(urlPathMatching("/resourceService/resources"))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(qfs))));

		QueryRequest request = new GeneralQueryRequest();

		//Should throw an error if credentials missing or wrong
		Map<String, String> credentials = new HashMap<String, String>();
		request.setResourceCredentials(credentials);
		String body = objectMapper.writeValueAsString(request);
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", headers, body);
		assertEquals("Missing credentials should return a 401",401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = objectMapper.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", headers, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
//		body = objectMapper.writeValueAsString(request);
//		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", headers, body);
//		assertEquals("Missing target URL should return a 500",500, response.getStatusLine().getStatusCode());
//		responseMessage = objectMapper.readTree(response.getEntity().getContent());
//		assertNotNull("Response message should not be null", responseMessage);
//		errorMessage = responseMessage.get("message").asText();
//		assertTrue("Error message should be " + ApplicationException.MISSING_TARGET_URL, errorMessage.contains(ApplicationException.MISSING_TARGET_URL));


		//This should work
		body = objectMapper.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/info", headers, body);
        assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
        JsonNode responseNode = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Response should not be null", responseNode);
        assertNotNull("Response should have a name", responseNode.get("name"));
        assertNotNull("Response should have data in queryFormats", responseNode.get("queryFormats"));
	}

	@Test
	public void testSearch() throws UnsupportedOperationException, IOException {
		HashMap<String, String> anyOldResult = new HashMap<>();
		anyOldResult.put("results", "aResult");

		HashMap<String, String> emptyResult = new HashMap<>();

		wireMockRule.stubFor(any(urlPathMatching("/resourceService/find"))
				.withHeader("Authorization", containing("anIncorrectToken"))
				.willReturn(aResponse()
						.withStatus(401)));

		wireMockRule.stubFor(any(urlEqualTo("/resourceService/find?term=%25antibody%25"))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(anyOldResult))));

		wireMockRule.stubFor(any(urlEqualTo("/resourceService/find?term=thisShouldFindNothing"))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(emptyResult))));


		QueryRequest queryRequest = new GeneralQueryRequest();
		Map<String, String> credentials = new HashMap<String, String>();
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery("%antibody%");

		String body = objectMapper.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", headers, body);
		assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		queryRequest.setResourceCredentials(credentials);
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", headers, body);
		assertEquals("Incorrect token should return a 401", 401, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
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
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", headers, body);
		assertEquals("Missing query string should return a 500",500, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		errorMessage = responseMessage.get("message").asText();
		assertEquals("Error message should be " + ProtocolException.MISSING_DATA, ProtocolException.MISSING_DATA, errorMessage);


		queryRequest.setQuery("%antibody%");
//		body = objectMapper.writeValueAsString(queryRequest);
//		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", headers, body);
//		assertEquals("Missing target URL should return a 500",500, response.getStatusLine().getStatusCode());
//		responseMessage = objectMapper.readTree(response.getEntity().getContent());
//		assertNotNull("Response message should not be null", responseMessage);
//		errorMessage = responseMessage.get("message").asText();
//		assertEquals("Error message should be " + ApplicationException.MISSING_TARGET_URL, ApplicationException.MISSING_TARGET_URL, errorMessage);

		//This should work
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", headers, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
        JsonNode responseNode = objectMapper.readTree(response.getEntity().getContent());
        assertNotNull("Result should not be null", responseNode);
        assertNotNull("Search results should not be null", responseNode.get("results"));
		assertFalse("Search results should not be empty", responseNode.get("results").size() == 0);
        assertEquals("Searchquery should match input query", "%antibody%", responseNode.get("searchQuery").asText());

        //Valid request with no results should return an empty result
		queryRequest.setQuery("thisShouldFindNothing");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/search", headers, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
		responseNode = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Result should not be null", responseNode);
		assertNotNull("Search results should not be null", responseNode.get("results"));
		assertTrue("Search results should be empty", responseNode.get("results").size() == 0);
		assertEquals("Searchquery should match input query", "thisShouldFindNothing", responseNode.get("searchQuery").asText());
    }

	@Test
	public void testQuery() throws UnsupportedOperationException, IOException {
		Map<String, String> resourceResponse = new HashMap<>();
		resourceResponse.put("resultId", "230958");
		resourceResponse.put("status", "AVAILABLE");

		wireMockRule.stubFor(any(urlPathMatching("/queryService/runQuery"))
				.withHeader("Authorization", containing("anIncorrectToken"))
				.willReturn(aResponse()
						.withStatus(401)));

		wireMockRule.stubFor(any(urlPathMatching("/queryService/runQuery"))
				.withRequestBody(equalTo("poorlyWordedQueryString"))
				.willReturn(aResponse()
						.withStatus(500)));

		wireMockRule.stubFor(any(urlPathMatching("/queryService/runQuery"))
				.withRequestBody(containing("/i2b2-nhanes/Demo"))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resourceResponse))));

		wireMockRule.stubFor(any(urlPathMatching("/resultService/resultStatus/.*"))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resourceResponse))));


		QueryRequest queryRequest = new GeneralQueryRequest();
		Map<String, String> credentials = new HashMap<String, String>();
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(queryString);
		String body = objectMapper.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
		assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		queryRequest.setResourceCredentials(credentials);
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

		//Should throw an error if missing query string
		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		queryRequest.setResourceCredentials(credentials);
		queryRequest.setQuery(null);
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
		assertEquals("Missing query string should return a 500",500, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + ProtocolException.MISSING_DATA, errorMessage.contains(ProtocolException.MISSING_DATA));

		//Try a poorly worded queryString
		queryRequest.setQuery("poorlyWordedQueryString");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
		assertEquals("Incorrectly formatted string should return 500",500, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "ri_error", errorType);
		/*errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));
*/

		queryRequest.setQuery(queryString);
//		body = objectMapper.writeValueAsString(queryRequest);
//		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
//		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
//		responseMessage = objectMapper.readTree(response.getEntity().getContent());
//		assertNotNull("Response message should not be null", responseMessage);
//		errorMessage = responseMessage.get("message").asText();
//		assertEquals("Error message should be " + ApplicationException.MISSING_TARGET_URL, ApplicationException.MISSING_TARGET_URL, errorMessage);

		JsonNode jsonNode = objectMapper.readTree(queryString);

		//Request can be an object that also requests the format
		ObjectNode queryNode = objectMapper.createObjectNode();
        queryNode.put("queryString", jsonNode);
		queryRequest.setQuery(queryNode);

		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
		assertEquals(200, response.getStatusLine().getStatusCode());
		QueryStatus result = readObjectFromResponse(response, QueryStatus.class);
		assertNotNull("Result should not be null", result);
		//Make sure all necessary fields are present
		assertNotNull("Status should not be null",result.getStatus());
		assertNotNull("ResourceResultId should not be null",result.getResourceResultId());

        //Or else just a query
        queryRequest.setQuery(jsonNode);
        body = objectMapper.writeValueAsString(queryRequest);
        response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query", headers, body);
        assertEquals(200, response.getStatusLine().getStatusCode());
        result = readObjectFromResponse(response, QueryStatus.class);
        assertNotNull("Result should not be null", result);
        //Make sure all necessary fields are present
        assertNotNull("Status should not be null",result.getStatus());
        assertNotNull("ResourceResultId should not be null",result.getResourceResultId());
    }

	@Test
	public void testQueryResult() throws UnsupportedOperationException, IOException {
		String resultResponse = "aResultOfSomeKind";

		wireMockRule.stubFor(any(urlPathMatching("/resultService/result/.*"))
				.withHeader("Authorization", containing("anIncorrectToken"))
				.willReturn(aResponse()
						.withStatus(401)));

		wireMockRule.stubFor(any(urlPathMatching("/resultService/result/111/.*"))
				.willReturn(aResponse()
						.withStatus(500)));

		wireMockRule.stubFor(any(urlPathMatching("/resultService/result/" + testQueryResultId + "/.*"))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resultResponse))));

		QueryRequest queryRequest = new GeneralQueryRequest();
	    Map<String, String> credentials = new HashMap<String, String>();
	    queryRequest.setResourceCredentials(credentials);
        String body = objectMapper.writeValueAsString(queryRequest);

		//Should throw an error if credentials missing or wrong
		HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", headers, body);
		assertEquals("Missing credentials should return a 401",401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", headers, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
        body = objectMapper.writeValueAsString(queryRequest);

        //TODO This is just returning what IRCT returns - do we need to test it?
		//False query id should return a failure message
        response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/111/result", headers, body);
		assertEquals("Should return a 500",500, response.getStatusLine().getStatusCode());

//		body = objectMapper.writeValueAsString(queryRequest);
//		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", headers, body);
//		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
//		responseMessage = objectMapper.readTree(response.getEntity().getContent());
//		assertNotNull("Response message should not be null", responseMessage);
//		errorMessage = responseMessage.get("message").asText();
//		assertEquals("Error message should be " + ApplicationException.MISSING_TARGET_URL, ApplicationException.MISSING_TARGET_URL, errorMessage);

        //This should work
		body = objectMapper.writeValueAsString(queryRequest);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/result", headers, body);
		assertEquals("Correct request should return a 200",200, response.getStatusLine().getStatusCode());
		String responseBody = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
		assertFalse("Response content should not be empty", responseBody.isEmpty());
	}

	@Test
	public void testQueryStatus() throws UnsupportedOperationException, IOException {
		Map<String, String> resourceResponse = new HashMap<>();
		resourceResponse.put("resultId", "230958");
		resourceResponse.put("status", "AVAILABLE");

		wireMockRule.stubFor(any(urlPathMatching("/resultService/resultStatus/.*"))
				.withHeader("Authorization", containing("anIncorrectToken"))
				.willReturn(aResponse()
						.withStatus(401)));

		wireMockRule.stubFor(any(urlPathMatching("/resultService/resultStatus/111/.*"))
				.willReturn(aResponse()
						.withStatus(500)));

		wireMockRule.stubFor(any(urlPathMatching("/resultService/resultStatus/" + testQueryResultId))
				.withHeader("Authorization", containing(token))
				.willReturn(aResponse()
						.withStatus(200)
						.withBody(objectMapper.writeValueAsString(resourceResponse))));

		QueryRequest request = new GeneralQueryRequest();
        Map<String, String>credentials = new HashMap<String, String>();
        request.setResourceCredentials(credentials);
        String body = objectMapper.writeValueAsString(request);

        //Should throw an error if credentials missing or wrong
        HttpResponse response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", headers, body);
		assertEquals("Missing credentials should return a 401", 401, response.getStatusLine().getStatusCode());
		JsonNode responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		String errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
		String errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));


		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, "anIncorrectToken");
		request.setResourceCredentials(credentials);
		body = objectMapper.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", headers, body);
		assertEquals("Incorrect token should return a 401",401, response.getStatusLine().getStatusCode());
		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "error", errorType);
/*		errorMessage = responseMessage.get("message").asText();
		assertTrue("Error message should be " + IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE, errorMessage.contains(IRCTResourceRS.MISSING_CREDENTIALS_MESSAGE));
*/

		credentials.put(IRCTResourceRS.IRCT_BEARER_TOKEN_KEY, token);
		request.setResourceCredentials(credentials);
//		body = objectMapper.writeValueAsString(request);
//		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", headers, body);
//		assertEquals("Missing target URL should return 500",500, response.getStatusLine().getStatusCode());
//		responseMessage = objectMapper.readTree(response.getEntity().getContent());
//		assertNotNull("Response message should not be null", responseMessage);
//		errorMessage = responseMessage.get("message").asText();
//		assertEquals("Error message should be " + ApplicationException.MISSING_TARGET_URL, ApplicationException.MISSING_TARGET_URL, errorMessage);

		//This should work
		body = objectMapper.writeValueAsString(request);
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/"+testQueryResultId+"/status", headers, body);
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
		response = retrievePostResponse(irctEndpointUrl+"pic-sure/v1.4/query/111/status", headers, body);
		//assertEquals("Nonexistent queryId should return a 500",500, response.getStatusLine().getStatusCode());

		responseMessage = objectMapper.readTree(response.getEntity().getContent());
		assertNotNull("Response message should not be null", responseMessage);
		errorType = responseMessage.get("errorType").asText();
		assertEquals("Error type should be error", "ri_error", errorType);

		//TODO Do we need to check for different statuses?  If so, how?
	}
}
