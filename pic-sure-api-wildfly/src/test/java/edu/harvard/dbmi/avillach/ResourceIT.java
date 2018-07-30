package edu.harvard.dbmi.avillach;

import static org.junit.Assert.*;

import org.apache.http.client.methods.HttpGet;
import org.junit.Test;

import edu.harvard.dbmi.avillach.data.entity.Resource;

import javax.ws.rs.core.HttpHeaders;

public class ResourceIT extends BaseIT{

	@Test
	public void testListResources() throws Exception {

		String jwt = generateJwtForSystemUser();
		HttpGet get = new HttpGet(endpointUrl + "/info/resources");
		get.setHeader(HttpHeaders.AUTHORIZATION, "Bearer "+ jwt);
		org.apache.http.HttpResponse response = client.execute(get);
		assertEquals("Response status code should be 200", 200, response.getStatusLine().getStatusCode());
		Resource[] resources = json.readValue(response.getEntity().getContent(), Resource[].class);

		assertEquals("The first resource should be named nhanes.hms.harvard.edu", "nhanes.hms.harvard.edu", resources[0].getName());
		assertEquals("The first resource should have description HMS DBMI NHANES PIC-SURE 1.4", "HMS DBMI NHANES PIC-SURE 1.4  Supply token with key 'IRCT_BEARER_TOKEN'", resources[0].getDescription());
		assertNotNull("The first resource should have a valid UUID", resources[0].getUuid());
	}

}
