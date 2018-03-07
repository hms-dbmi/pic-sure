package edu.harvard.dbmi.avillach;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.*;

@Path("/")
@Produces("application/json")
@Consumes("application/json")
public class PicsureRS {
	
	@Inject
	PicsureInfoService infoService;
	
	@Inject
	PicsureSearchService searchService;
	
	@Inject
	PicsureQueryService queryService;
	
	@GET
	@Path("/info")
	public ResourceInfo info() {		
		return infoService.info();
	}
	
	@POST
	@Path("/info/{resourceId}")
	public ResourceInfo resourceInfo(@PathParam("resourceId") String resourceId, Map<String, String> resourceCredentials) {	
		System.out.println("Resource info requested for : " + resourceId);
		return infoService.info(UUID.fromString(resourceId), resourceCredentials);
	}
	
	@GET
	@Path("/info/resources")
	public List<Resource> resources(){
		return infoService.resources();
	}
	
	@POST
	@Path("/search")
	public SearchResults search(String searchJson) {
		return searchService.search(searchJson);
	}
	
	@POST
	@Path("/query")
	public QueryResults query(String queryJson) {
		return queryService.query(queryJson);
	}
	
	@GET
	@Path("/query/{resourceQueryId}/status)")
	public QueryStatus queryStatus(UUID queryId) {
		return queryService.queryStatus(queryId);
	}
	
	@GET
	@Path("/query/{resourceQueryId}/result")
	public QueryResults queryResult(UUID queryId) {
		return queryService.queryResult(queryId);
	}
	
}
