package edu.harvard.dbmi.avillach.service;

import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import edu.harvard.dbmi.avillach.domain.QueryResults;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;

@Path("/pic-sure")
@Produces("application/json")
public interface IResourceRS 
{
    
	@GET
	@Path("/info")
	public ResourceInfo info();
	
	@POST
	@Path("/search")
	public SearchResults search(String searchJson);
	
	@POST
	@Path("/query")
	public QueryResults query(String queryJson);
	
	@GET
	@Path("/query/{resourceQueryId}/status)")
	public QueryStatus queryStatus(UUID queryId);
	
	@GET
	@Path("/query/{resourceQueryId}/result")
	public QueryResults queryResult(UUID queryId);
	
}
