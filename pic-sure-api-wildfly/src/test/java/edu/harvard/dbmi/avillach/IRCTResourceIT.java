package edu.harvard.dbmi.avillach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import jdk.incubator.http.HttpRequest;
import jdk.incubator.http.HttpResponse;
import jdk.incubator.http.HttpResponse.BodyHandler;

public class IRCTResourceIT extends BaseIT {

	private static UUID nhanesResourceId = null;
	
	@BeforeClass
	public static void findNHANESResourceId() {
		BaseIT.beforeClass();
		String jwt = generateJwtForSystemUser();
		HttpResponse<String> response;
		try {
			response = client.send(
					HttpRequest.newBuilder()
					.GET()
					.uri(URI.create(endpointUrl + "info/resources"))
					.header("Authorization", "Bearer "+ jwt)
					.build(), BodyHandler.asString());
			List<Resource> resources = new ObjectMapper().
					readValue(response.body(), new TypeReference<List<Resource>>() {});
			nhanesResourceId = resources.stream().filter(it -> {
				return it.getName().equalsIgnoreCase("nhanes.hms.harvard.edu");
			}).findFirst().get().getUuid();
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testResourceInfo() throws Exception {
		String jwt = generateJwtForSystemUser();
		System.out.println(endpointUrl + "info/" + nhanesResourceId);
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder()
				.GET()
				.uri(URI.create(endpointUrl + "info/" + nhanesResourceId))
				.header("Authorization", "Bearer "+ jwt)
				.header("Content-type", "application/json")
				.build(), BodyHandler.asString());

		System.out.println(response.body());
		System.out.println(response.statusCode());
	}
}
