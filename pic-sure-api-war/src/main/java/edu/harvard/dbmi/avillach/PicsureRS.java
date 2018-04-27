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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

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
	public ResourceInfo resourceInfo(@PathParam("resourceId") String resourceId, Map<String, String> resourceCredentials, @Context HttpHeaders headers) {
		System.out.println("Resource info requested for : " + resourceId);
		return infoService.info(UUID.fromString(resourceId), resourceCredentials, headers);
	}
	
	@GET
	@Path("/info/resources")
	public List<Resource> resources(){
		return infoService.resources();
	}
	
	@POST
	@Path("/search/{resourceId}")
	public SearchResults search(@PathParam("resourceId") UUID resourceId, QueryRequest searchQueryRequest, @Context HttpHeaders headers) {
		return searchService.search(resourceId, searchQueryRequest, headers);
	}
	
	@POST
	@Path("/query/{resourceId}")
	public QueryStatus query(@PathParam("resourceId") UUID resourceId, QueryRequest dataQueryRequest, @Context HttpHeaders headers) {
		return queryService.query(resourceId, dataQueryRequest, headers);
	}
	
	@POST
	@Path("/query/{queryId}/status")
	public QueryStatus queryStatus(@PathParam("queryId") UUID queryId, Map<String, String> resourceCredentials, @Context HttpHeaders headers) {
		return queryService.queryStatus(queryId, resourceCredentials, headers);
	}
	
	@POST
	@Path("/query/{queryId}/result")
	public Response queryResult(@PathParam("queryId") UUID queryId, Map<String, String> resourceCredentials, @Context HttpHeaders headers) {
		return queryService.queryResult(queryId, resourceCredentials, headers);
	}
	
}
