package edu.harvard.dbmi.avillach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import javax.ws.rs.core.HttpHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.Test;


public class SystemServiceIT extends BaseIT {

	@Test
	public void testStatusIsAccessibleToSystemUser() throws Exception {
		try {
			String jwt = generateJwtForSystemUser();
			HttpGet get = new HttpGet(endpointUrl + "/system/status");
			get.setHeader(HttpHeaders.AUTHORIZATION, "Bearer "+ jwt);
			HttpResponse response = client.execute(get);
			assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
			assertEquals("System status should be RUNNING'", "RUNNING", EntityUtils.toString(response.getEntity().getContent(), "UTF-8"));

		}catch(Exception e) {
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testStatusIsNotAccessibleToNonSystemUser()  {
		
		try{
			String jwt = generateJwtForNonSystemUser();

			HttpGet get = new HttpGet(endpointUrl + "/system/status");
			get.setHeader(HttpHeaders.AUTHORIZATION, "Bearer "+ jwt);
			HttpResponse response = client.execute(get);
			assertEquals("Request should be rejected with 401", 401, response.getStatusLine().getStatusCode());
			JsonNode responseBody = objectMapper.readTree(response.getEntity().getContent());
			assertEquals("Message should report: User is not authorized. [doesn't match the required role restrictions.]", "User is not authorized. [doesn't match the required role restrictions.]", responseBody.get("message").asText());

		}catch(Exception e) {
			fail("Exception: " + e.getMessage());
		}
	}


}
