package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

import javax.ws.rs.core.HttpHeaders;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class TokenServiceIT extends BaseIT {

	@Test
	public void testInspectToken() throws Exception {
		//A perfectly valid token
		String tokenJwt = generateJwtForCallingTokenInspection();
		String jwt = generateJwtForTokenInspectionUser();
		Header[] headers = new Header[1];
		headers[0] = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenJwt);
		Map<String,String> tokenMap = new HashMap<>();
		tokenMap.put("token", jwt);
		String tokenString = json.writeValueAsString(tokenMap);
		HttpResponse response = HttpClientUtil.retrievePostResponse(endpointUrl + "/token/inspect", headers, tokenString);
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
		JsonNode responseNode = json.readTree(response.getEntity().getContent());
		assertNotNull("Response entity should not be null", responseNode);
		String email = responseNode.get("email").asText();
		assertEquals("Email should match token", email, "foo4@bar.com");
		//Todo should this be in a different form
		assert(Boolean.parseBoolean(responseNode.get("active").asText()));

		//An invalid token
		String jwt2 = generateJwtForNonSystemUser();
		tokenMap.put("token", jwt2);
		tokenString = json.writeValueAsString(tokenMap);
		response = HttpClientUtil.retrievePostResponse(endpointUrl + "/token/inspect", headers, tokenString);
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
		responseNode = json.readTree(response.getEntity().getContent());
		assertNotNull("Response entity should not be null", responseNode);
		assertFalse(Boolean.parseBoolean(responseNode.get("active").asText()));

		//Missing token
		tokenMap = new HashMap<>();
		tokenString = json.writeValueAsString(tokenMap);
		response = HttpClientUtil.retrievePostResponse(endpointUrl + "/token/inspect", headers, tokenString);
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
		responseNode = json.readTree(response.getEntity().getContent());
		assertNotNull("Response entity should not be null", responseNode);
		assertFalse(Boolean.parseBoolean(responseNode.get("active").asText()));

		//Expired token
		String jwt3 = generateExpiredJwt();
		tokenMap.put("token", jwt3);
		tokenString = json.writeValueAsString(tokenMap);
		response = HttpClientUtil.retrievePostResponse(endpointUrl + "/token/inspect", headers, tokenString);
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
		responseNode = json.readTree(response.getEntity().getContent());
		assertNotNull("Response entity should not be null", responseNode);
		assertFalse(Boolean.parseBoolean(responseNode.get("active").asText()));
	}


}
