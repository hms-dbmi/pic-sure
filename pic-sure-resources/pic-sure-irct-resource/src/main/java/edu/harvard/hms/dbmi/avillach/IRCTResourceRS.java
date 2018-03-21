package edu.harvard.hms.dbmi.avillach;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import edu.harvard.dbmi.avillach.service.IResourceRS;
import org.apache.http.message.BasicHeader;

import static edu.harvard.dbmi.avillach.service.HttpClientUtil.*;


@Path("/v1.4")
@Produces("application/json")
@Consumes("application/json")
public class IRCTResourceRS implements IResourceRS
{
	private static final String TARGET_IRCT_URL = System.getenv("TARGET_IRCT_URL");
//	private static final UUID TARGET_UUID = UUID.fromString(System.getProperty("TEST_UUID"));
	public static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";
	private static final String AUTHORIZATION = "AUTHORIZATION";
	private static final String BEARER_STRING = "Bearer ";
	public IRCTResourceRS() {
		if(TARGET_IRCT_URL == null)
			throw new RuntimeException("TARGET_IRCT_URL environment variable must be set.");
		/*if(TARGET_UUID == null)
			throw new RuntimeException("TEST_UUID must be set");*/
	}
	
	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}
	
	@POST
	@Path("/info")
	@Override
	public ResourceInfo info(Map<String,String> resourceCredentials) {
		if (resourceCredentials == null){
			throw new RuntimeException("Missing credentials");
		}
		String pathName = "/resourceService/resources";
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		if (token == null){
			throw new RuntimeException("Missing credentials");
		}
		Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
		Header[] headers = new Header[1];
		headers[0] = authorizationHeader;
		HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
		if (resourcesResponse.getStatusLine().getStatusCode() != 200){
			throw new RuntimeException("Resource did not return a 200");
		}
		//TODO: How do we get the ID in real life?
		return new ResourceInfo().setName("IRCT Resource : " + TARGET_IRCT_URL)
			//	.setId(TARGET_UUID)
				.setQueryFormats(
						readListFromResponse(resourcesResponse, QueryFormat.class));
	}

	@POST
	@Path("/search")
	@Override
	public SearchResults search(QueryRequest searchJson) {
		try {
			if (searchJson == null) {
				throw new RuntimeException("Missing query request data");
			}
			Map<String, String> resourceCredentials = searchJson.getResourceCredentials();
			if (resourceCredentials == null) {
				throw new RuntimeException("Missing credentials");
			}
			String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
			if (token == null) {
				throw new RuntimeException("Missing credentials");
			}
			String searchTerm = searchJson.getQuery();
			if (searchTerm == null) {
				throw new RuntimeException(("Missing query request data"));
			}
			Header authorizationHeader = new BasicHeader(AUTHORIZATION, BEARER_STRING + token);
			Header[] headers = new Header[1];
			headers[0] = authorizationHeader;
			String pathName = "/resourceService/find?term=" + URLEncoder.encode(searchTerm, "UTF-8");
			HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, headers);
			if (resourcesResponse.getStatusLine().getStatusCode() != 200) {
				throw new RuntimeException("Resource did not return a 200");
			}
			SearchResults results = new SearchResults();
			results.setSearchQuery(searchTerm);
			results.setResults(readObjectFromResponse(resourcesResponse, Object.class));
			return results;
		} catch (UnsupportedEncodingException e){
			//TODO what to do about this
			throw new RuntimeException(e);
		}
	}

	@POST
	@Path("/query")
	@Override
	public QueryResults query(String queryJson) {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/query/{resourceQueryId}/status)")
	@Override
	public QueryStatus queryStatus(@PathParam("resourceQueryId") UUID queryId, Map<String, String> resourceCredentials) {
		// TODO Auto-generated method stub
		return null;
	}

	@POST
	@Path("/query/{resourceQueryId}/result")
	@Override
	public QueryResults queryResult(@PathParam("resourceQueryId") UUID queryId, Map<String, String> resourceCredentials) {
		// TODO Auto-generated method stub
		return null;
	}


}
