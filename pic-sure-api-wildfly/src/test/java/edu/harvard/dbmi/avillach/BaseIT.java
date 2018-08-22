package edu.harvard.dbmi.avillach;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.BeforeClass;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.http.client.HttpClient;

public class BaseIT {
	private static final String CLIENT_SECRET = System.getenv("PIC_SURE_CLIENT_SECRET");

	protected static String endpointUrl;
	protected static String irctEndpointUrl;

	protected static HttpClient client = HttpClientBuilder.create().build();
	protected final static ObjectMapper json = new ObjectMapper();

	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
		irctEndpointUrl = System.getProperty("irct.service.url");
	}

	/* These users are initialized in the database in the UserTestInitializer class. An instance
	 * of which is declared here for your IDE navigation convenience.
	 */
	UserTestInitializer whereYourTestDataLives;
	
	protected static String generateJwtForSystemUser() {
		return Jwts.builder()
				.setSubject("samlp|foo@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, CLIENT_SECRET.getBytes())
				.compact();
	}

	public String generateJwtForNonSystemUser() {
		return Jwts.builder()
				.setSubject("samlp|foo2@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo2@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, CLIENT_SECRET.getBytes())
				.compact();
	}
}
