package edu.harvard.dbmi.avillach;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.junit.BeforeClass;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.client.HttpClient;
import org.junit.Rule;

import javax.ws.rs.core.HttpHeaders;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.composeURL;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.retrieveGetResponse;
import static edu.harvard.dbmi.avillach.service.HttpClientUtil.retrievePostResponse;
import static org.junit.Assert.*;

public class BaseIT {

	private static final String CLIENT_SECRET = System.getenv("PIC_SURE_CLIENT_SECRET");
	private static final String USER_ID_CLAIM = System.getenv("PIC_SURE_USER_ID_CLAIM");

	protected static String endpointUrl;
	protected static String irctEndpointUrl;
	protected static String aggregate_url;

	protected static UUID resourceId;

	protected static List<Header> headers = new ArrayList<>();

	protected static HttpClient client = HttpClientBuilder.create().build();
	protected final static ObjectMapper objectMapper = new ObjectMapper();

	protected final static int port = 8079;
	protected final static String testURL = "http://localhost:"+port;

	@Rule
	public WireMockClassRule wireMockRule = new WireMockClassRule(port);

	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
		System.out.println("endpointUrl is: " + endpointUrl);
		irctEndpointUrl = System.getProperty("irct.rs.url");
		System.out.println("irctEndpointUrl is: " + irctEndpointUrl);
		aggregate_url = System.getProperty("aggregate.rs.url");


		String jwt = generateJwtForSystemUser();
		headers.add(new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt));
		headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"));

		//insert a resource for testing if necessary
		try {
			String uri = composeURL(endpointUrl, "/resource");

			HttpResponse response = retrieveGetResponse(uri, headers);
			assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
			List<Resource> resources = objectMapper.readValue(response.getEntity().getContent(), new TypeReference<List<Resource>>() {
			});
			assertFalse(resources.isEmpty());

			String resourceRSPath = null;
			boolean testResourceInserted = false;
			for (Resource r : resources){
				if ("Test Resource".equals(r.getName())){
					testResourceInserted = true;
					resourceId = r.getUuid();
					break;
				} else if (resourceRSPath == null){
					//We'll need a random resourceRSPath for testing
					resourceRSPath = r.getResourceRSPath();
				}
			}

			if (!testResourceInserted){
				resources = new ArrayList<>();
				Resource testResource = new Resource();
				testResource.setResourceRSPath(resourceRSPath);
				testResource.setDescription("Test Resource");
				testResource.setName("Test Resource");
				testResource.setToken("testToken");
				testResource.setTargetURL(testURL);
				resources.add(testResource);
				response = retrievePostResponse(uri, headers, objectMapper.writeValueAsString(resources));
				assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
				List<JsonNode> responseBody = objectMapper.readValue(response.getEntity().getContent(), new TypeReference<List<JsonNode>>(){});
				assertFalse(responseBody.isEmpty());
				String id = responseBody.get(0).get("uuid").asText();
				assertNotNull("Resource response should have an id", id);
				resourceId = UUID.fromString(id);
			}
		} catch(IOException e) {
			fail("Unable to set up test resource");
		}
	}

	/* These users are initialized in the database in the UserTestInitializer class. An instance
	 * of which is declared here for your IDE navigation convenience.
	 */
	UserTestInitializer whereYourTestDataLives;
	
	protected static String generateJwtForSystemUser() {
		return Jwts.builder()
				.setSubject("samlp|foo@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date())
				.addClaims(Map
						.of(USER_ID_CLAIM,"foo@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, CLIENT_SECRET.getBytes())
				.compact();
	}

	public String generateJwtForNonSystemUser() {
		return Jwts.builder()
				.setSubject("samlp|foo2@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of(USER_ID_CLAIM,"foo2@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, CLIENT_SECRET.getBytes())
				.compact();
	}

	public String generateJwtForCallingTokenInspection() {
		return Jwts.builder()
				.setSubject("samlp|foo3@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo3@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, "foo".getBytes())
				.compact();
	}

	public String generateJwtForTokenInspectionUser() {
		return Jwts.builder()
				.setSubject("samlp|foo4@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo4@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, "foo".getBytes())
				.compact();
	}

	public String generateExpiredJwt() {
		return Jwts.builder()
				.setSubject("samlp|foo@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().minusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, "foo".getBytes())
				.compact();
	}
}
