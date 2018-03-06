package edu.harvard.dbmi.avillach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import javax.ws.rs.core.HttpHeaders;

import org.junit.Test;

import jdk.incubator.http.HttpRequest;
import jdk.incubator.http.HttpResponse;
import jdk.incubator.http.HttpResponse.BodyHandler;

public class SystemServiceIT extends BaseIT {

	@Test
	public void testStatusIsAccessibleToSystemUser() throws Exception {
		String jwt = generateJwtForSystemUser();
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder()
				.GET()
				.uri(URI.create(endpointUrl + "system/status"))
				.header("Authorization", "Bearer "+ jwt)
				.build(),
				BodyHandler.asString());
		assertEquals("System status should be RUNNING", "RUNNING", response.body());
		assertEquals("Response status code should be 200", 200, response.statusCode());
	}

	@Test
	public void testStatusIsNotAccessibleToNonSystemUser()  {
		
		/* This is pretty nasty. Unfortunately a 401 response results in an IOException
		 * from the HttpClient. This means we can't check the body of the response or
		 * anything else. We also have to assume that an IOException with the hard coded
		 * message of "Invalid auth header" can only mean 401, which is probably safe, but
		 * feels really really dirty.
		 * 
		 * Note: PIC-SURE actually returns a meaningful message with its 401, but the test
		 * cannot assert it. I am open to using some other HttpClient or WebClient 
		 * implementation if we run into too many of these types of issues.
		 */
		try{
			String jwt = generateJwtForNonSystemUser();
			client.send(
					HttpRequest.newBuilder()
					.GET()
					.uri(URI.create(endpointUrl + "system/status"))
					.header(HttpHeaders.AUTHORIZATION, "Bearer "+ jwt)
					.build(), BodyHandler.asString());
		}catch(Exception e) {
			assertTrue("Exception thrown should be IOException", IOException.class.isAssignableFrom(e.getClass()));
			assertEquals("Request should be rejected with 401", "Invalid auth header", e.getMessage());
		}
	}


}
