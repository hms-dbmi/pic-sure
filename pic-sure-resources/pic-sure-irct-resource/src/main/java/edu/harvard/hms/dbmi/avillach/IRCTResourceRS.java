package edu.harvard.hms.dbmi.avillach;

import java.util.Map;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.apache.http.HttpResponse;

import edu.harvard.dbmi.avillach.domain.QueryFormat;
import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.service.IResourceRS;

import static edu.harvard.hms.dbmi.avillach.HttpClientUtil.*;


@Path("/v1.4")
@Produces("application/json")
@Consumes("application/json")
public class IRCTResourceRS implements IResourceRS
{
	private static final String TARGET_IRCT_URL = System.getenv("TARGET_IRCT_URL");
	private static final String IRCT_BEARER_TOKEN_KEY = "IRCT_BEARER_TOKEN";
	public IRCTResourceRS() {
		if(TARGET_IRCT_URL == null)
			throw new RuntimeException("TARGET_IRCT_URL environment variable must be set.");
	}
	
	@GET
	@Path("/status")
	public Response status() {
		return Response.ok().build();
	}
	
	@POST
	@Path("/info")
	@Override
	public ResourceInfo info(Map<String, String> resourceCredentials) {
		String token = resourceCredentials.get(IRCT_BEARER_TOKEN_KEY);
		String pathName = "resourceService/resources";
		HttpResponse resourcesResponse = retrieveGetResponse(TARGET_IRCT_URL + pathName, token);
		return new ResourceInfo().setName("IRCT Resource : " + TARGET_IRCT_URL)
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


}
