package edu.harvard.dbmi.avillach;

import static org.junit.Assert.assertEquals;

import javax.ws.rs.core.HttpHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.service.HttpClientUtil;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

public class SystemServiceIT extends BaseIT {

	@Test
	public void testStatusEndpointSuccceedsWithSystemUser() throws Exception {
		String jwt = generateJwtForSystemUser();
		Header[] headers = new Header[1];
		headers[0] = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
		HttpResponse response = HttpClientUtil.retrieveGetResponse(endpointUrl + "/system/status", headers);
		assertEquals("System status should be RUNNING", "RUNNING", IOUtils.toString(response.getEntity().getContent(), "UTF-8"));
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
	}

	@Test
	public void testStatusEndpointFailsWithNonSystemUser()  {
		try{
			String jwt = generateJwtForNonSystemUser();
			Header[] headers = new Header[1];
			headers[0] = new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
			HttpResponse response = HttpClientUtil.retrieveGetResponse(endpointUrl + "/system/status", headers);
			JsonNode errorMessage = json.readTree(response.getEntity().getContent());
			assertEquals("User has insufficient privileges.", "User has insufficient privileges.", errorMessage.get("message").asText());
			assertEquals("Response status code should be 401", 401, response.getStatusLine().getStatusCode());
		}catch(Exception e) {
			e.printStackTrace();
		}
	}

}
