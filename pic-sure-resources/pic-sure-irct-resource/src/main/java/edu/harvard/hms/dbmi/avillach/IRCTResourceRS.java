package edu.harvard.hms.dbmi.avillach;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.exception.ResourceCommunicationException;
import edu.harvard.dbmi.avillach.service.IResourceRS;


@Path("/pic-sure/v1.4")
@Produces("application/json")
public class IRCTResourceRS implements IResourceRS
{
	private final ObjectMapper json = new ObjectMapper();
	
	@GET
	@Path("/info")
	@Override
	public ResourceInfo info() {
		URI targetResource = URI.create("https://nhanes.hms.harvard.edu/rest/v1");
		String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0fGF2bGJvdEBkYm1pLmhtcy5oYXJ2YXJkLmVkdSIsImVtYWlsIjoiYXZsYm90QGRibWkuaG1zLmhhcnZhcmQuZWR1In0.51TYsm-uw2VtI8aGawdggbGdCSrPJvjtvzafd2Ii9NU";
		String pathName = "/resourceService/resources";
		String resourcesResponse = retrieveStringResponse(targetResource, pathName, token);
		return new ResourceInfo().setName("IRCT Resource")
				.setQueryFormats(
						readListFromResponse(resourcesResponse, QueryFormat.class));
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(String searchJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/query")
	@Override
	public QueryResults query(String queryJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/status)")
	@Override
	public QueryStatus queryStatus(UUID queryId) {
		// TODO Auto-generated method stub
		return null;
	}

	@GET
	@Path("/query/{resourceQueryId}/result")
	@Override
	public QueryResults queryResult(UUID queryId) {
		// TODO Auto-generated method stub
		return null;
	}

	private String retrieveStringResponse(URI targetResource, String pathName, String token) {
		try {
			HttpClient client = HttpClientBuilder.create().build();
			HttpGet get = new HttpGet(targetResource + pathName);
			get.addHeader("AUTHORIZATION", "Bearer " + token);
			HttpResponse response = client.execute(get);
			return IOUtils.toString(response.getEntity().getContent(), "UTF-8");
		} catch (IOException e) {
			throw new ResourceCommunicationException(targetResource, pathName, e);
		}
	}

	private <T> List<T> readListFromResponse(String resourcesResponse, Class<T> expectedElementType) {
		try {
			return json.readValue(resourcesResponse, new TypeReference<List<T>>() {});
		} catch (IOException e) {
			e.printStackTrace();
			return new ArrayList<T>();
		}
	}

}
