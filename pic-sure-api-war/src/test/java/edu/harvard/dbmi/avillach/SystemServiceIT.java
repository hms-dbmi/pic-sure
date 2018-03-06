package edu.harvard.dbmi.avillach;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import javax.ws.rs.core.HttpHeaders;

import org.junit.BeforeClass;
import org.junit.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jdk.incubator.http.HttpClient;
import jdk.incubator.http.HttpRequest;
import jdk.incubator.http.HttpResponse;
import jdk.incubator.http.HttpResponse.BodyHandler;

public class SystemServiceIT {
	private static String endpointUrl;

	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
	}

	@Test
	public void testStatusEndpointSuccceedsWithSystemUser() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		System.out.println(endpointUrl);
		String jwt = generateJwtUser1();
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder()
				.GET()
				.uri(URI.create(endpointUrl + "system/status"))
				.header(HttpHeaders.AUTHORIZATION, "Bearer "+ jwt)
				.build(),
				BodyHandler.asString());
		assertEquals("System status should be RUNNING", "RUNNING", response.body());
		assertEquals("Response status code should be 200", 200, response.statusCode());
	}

	@Test
	public void testStatusEndpointFailsWithNonSystemUser()  {
		try{
			HttpClient client = HttpClient.newHttpClient();
		System.out.println(endpointUrl);
		String jwt = generateJwtUser2();
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder()
				.GET()
				.uri(URI.create(endpointUrl + "system/status"))
				.header(HttpHeaders.AUTHORIZATION, "Bearer "+ jwt)
				.build(),
				BodyHandler.asString());
		assertEquals("System status should be RUNNING", "User has insufficient privileges.", response.body());
		assertEquals("Response status code should be 401", 401, response.statusCode());
		}catch(Exception e) {
			e.printStackTrace();
		}
	}

	public String generateJwtUser1() {
		return Jwts.builder()
				.setSubject("samlp|foo@bar.com")
				.setIssuer("http://localhost:8080")
				.setIssuedAt(new Date()).addClaims(Map.of("email","foo@bar.com"))
				.setExpiration(Date.from(LocalDateTime.now().plusMinutes(15L).atZone(ZoneId.systemDefault()).toInstant()))
				.signWith(SignatureAlgorithm.HS512, Base64.getEncoder().encode("bar".getBytes()))
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
}
