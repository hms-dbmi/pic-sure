package edu.harvard.dbmi.avillach.service;

import java.util.Map;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;

@Path("/pic-sure")
@Produces("application/json")
@Consumes("application/json")
public interface IResourceRS 
{
    
	@GET
	@Path("/info")
	public ResourceInfo info(Map<String, String> resourceCredentials);
	
	@POST
	@Path("/search")
	public SearchResults search(QueryRequest searchJson);
	
	@POST
	@Path("/query")
	public QueryStatus query(QueryRequest queryJson);
	
	@POST
	@Path("/query/{resourceQueryId}/status")
	public QueryStatus queryStatus(String queryId, Map<String, String> resourceCredentials);
	
	@POST
	@Path("/query/{resourceQueryId}/result")
	public Response queryResult(String queryId, Map<String, String> resourceCredentials);
	
}
