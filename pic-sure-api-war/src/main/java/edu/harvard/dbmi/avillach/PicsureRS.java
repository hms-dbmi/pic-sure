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
	@Path("/search/{resourceId}")
	public SearchResults search(@PathParam("resourceId") UUID resourceId, QueryRequest searchQueryRequest) {
		return searchService.search(resourceId, searchQueryRequest);
	}
	
	@POST
	@Path("/query/{resourceId}")
	public QueryResults query(@PathParam("resourceId") UUID resourceId, QueryRequest dataQueryRequest) {
		return queryService.query(resourceId, dataQueryRequest);
	}
	
	@GET
	@Path("/query/{queryId}/status)")
	public QueryStatus queryStatus(@PathParam("queryId") UUID queryId, Map<String, String> resourceCredentials) {
		return queryService.queryStatus(queryId, resourceCredentials);
	}
	
	@GET
	@Path("/query/{queryId}/result")
	public QueryResults queryResult(@PathParam("queryId") UUID queryId, Map<String, String> resourceCredentials) {
		return queryService.queryResult(queryId, resourceCredentials);
	}
	
}
