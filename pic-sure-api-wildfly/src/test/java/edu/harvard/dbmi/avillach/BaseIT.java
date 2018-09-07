package edu.harvard.dbmi.avillach;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.junit.BeforeClass;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.client.HttpClient;

import javax.ws.rs.core.HttpHeaders;

public class BaseIT {

	private static final String CLIENT_SECRET = System.getenv("PIC_SURE_CLIENT_SECRET");
	private static final String USER_ID_CLAIM = System.getenv("PIC_SURE_USER_ID_CLAIM");

	protected static String endpointUrl;
	protected static String irctEndpointUrl;
	protected static String aggregate_url = System.getProperty("aggregate.rs.url");

	protected static List<Header> headers = new ArrayList<>();

	protected static HttpClient client = HttpClientBuilder.create().build();
	protected final static ObjectMapper objectMapper = new ObjectMapper();

	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
		System.out.println("endpointUrl is: " + endpointUrl);
		irctEndpointUrl = System.getProperty("irct.rs.url");
		System.out.println("irctEndpointUrl is: " + irctEndpointUrl);

		headers.add(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"));
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
}
