package edu.harvard.dbmi.avillach;

import static org.junit.Assert.*;

import java.net.URI;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import jdk.incubator.http.HttpRequest;
import jdk.incubator.http.HttpResponse;
import jdk.incubator.http.HttpResponse.BodyHandler;

public class ResourceIT extends BaseIT{

	@Test
	public void testListResources() throws Exception {
		String jwt = generateJwtForSystemUser();
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder()
				.GET()
				.uri(URI.create(endpointUrl + "info/resources"))
				.header("Authorization", "Bearer "+ jwt)
				.build(), BodyHandler.asString());
		
		Resource[] resources = new ObjectMapper().readValue(response.body(), Resource[].class);
		assertEquals("There should be 1 resource", 1, resources.length);
		
		assertEquals("The first resource should be named nhanes.hms.harvard.edu", "nhanes.hms.harvard.edu", resources[0].getName());
		assertEquals("The first resource should have url https://nhanes.hms.harvard.edu/rest/v1", "https://nhanes.hms.harvard.edu/rest/v1", resources[0].getBaseUrl());
		assertEquals("The first resource should have description HMS DBMI NHANES PIC-SURE 1.4", "HMS DBMI NHANES PIC-SURE 1.4", resources[0].getDescription());
		assertNotNull("The first resource should have a valid UUID", resources[0].getUuid());
		assertEquals("Response status code should be 200", 200, response.statusCode());
	}

}
