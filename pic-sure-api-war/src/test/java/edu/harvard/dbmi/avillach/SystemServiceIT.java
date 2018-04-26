package edu.harvard.dbmi.avillach;

import static org.junit.Assert.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import javax.ws.rs.core.HttpHeaders;

import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.junit.BeforeClass;
import org.junit.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class SystemServiceIT {
	private static String endpointUrl;

	@BeforeClass
	public static void beforeClass() {
		endpointUrl = System.getProperty("service.url");
	}

	@Test
	public void testStatusEndpointSuccceedsWithSystemUser() throws Exception {
		String jwt = generateJwtUser1();
		Header[] headers = new Header[1];
		headers[0] = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
		HttpResponse response = HttpClientUtil.retrieveGetResponse(endpointUrl + "/system/status", headers);
		assertEquals("System status should be RUNNING", "RUNNING", IOUtils.toString(response.getEntity().getContent(), "UTF-8"));
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testStatusEndpointFailsWithNonSystemUser()  {
		try{
			String jwt = generateJwtUser2();
			Header[] headers = new Header[1];
			headers[0] = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
			HttpResponse response = HttpClientUtil.retrieveGetResponse(endpointUrl + "/system/status", headers);

			assertEquals("System status should be RUNNING", "User has insufficient privileges.", IOUtils.toString(response.getEntity().getContent(), "UTF-8"));
			assertEquals("Response status code should be 401", 401, response.getStatusLine().getStatusCode());
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
