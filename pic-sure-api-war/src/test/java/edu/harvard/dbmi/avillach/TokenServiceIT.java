package edu.harvard.dbmi.avillach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.core.HttpHeaders;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class TokenServiceIT {
	private static String endpointUrl;
	private final static ObjectMapper json = new ObjectMapper();


	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
	}

	@Test
	public void testInspectToken() throws Exception {
		//A perfectly valid token
		String jwt = generateJwtUser1();
		Header[] headers = new Header[1];
		headers[0] = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
		Map<String,String> tokenMap = new HashMap<>();
		tokenMap.put("token", jwt);
		String tokenString = json.writeValueAsString(tokenMap);
		HttpResponse response = HttpClientUtil.retrievePostResponse(endpointUrl + "/token/inspect", headers, tokenString);
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
		JsonNode responseNode = json.readTree(response.getEntity().getContent());
		assertNotNull("Response entity should not be null", responseNode);
		String email = responseNode.get("email").asText();
		assertEquals("Email should match token", email, "foo@bar.com");
		//Todo should this be in a different form
		assert(Boolean.parseBoolean(responseNode.get("active").asText()));

		//An invalid token
		String jwt2 = generateJwtUser2();
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

	public String generateJwtUser1() {
		return Jwts.builder()
				.setSubject("samlp|foo@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, Base64.getEncoder().encode("foo".getBytes()))
				.compact();
	}

	public String generateJwtUser2() {
		return Jwts.builder()
				.setSubject("samlp|foo2@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo2@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, Base64.getEncoder().encode("bar".getBytes()))
				.compact();
	}

	public String generateExpiredJwt() {
		return Jwts.builder()
				.setSubject("samlp|foo@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().minusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, Base64.getEncoder().encode("foo".getBytes()))
				.compact();
	}
}
