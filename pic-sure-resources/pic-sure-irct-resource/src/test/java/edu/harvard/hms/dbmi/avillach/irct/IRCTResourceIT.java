package edu.harvard.hms.dbmi.avillach.irct;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.IRCTResourceRS;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.junit.Test;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;
import static org.junit.Assert.*;


import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class IRCTResourceIT extends BaseIT {

	private final static String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
	private final static ObjectMapper json = new ObjectMapper();

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
}
