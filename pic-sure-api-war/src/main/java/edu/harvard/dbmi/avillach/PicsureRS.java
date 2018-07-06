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
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

@Path("/")
@Produces("application/json")
@Consumes("application/json")
@Api
public class PicsureRS {
	
	@Inject
	PicsureInfoService infoService;
	
	@Inject
	PicsureSearchService searchService;
	
	@Inject
	PicsureQueryService queryService;

	@POST
	@Path("/info/{resourceId}")
	@ApiOperation(value = "Returns information about the provided resource")
	public ResourceInfo resourceInfo(@ApiParam(value="The UUID of the resource to fetch information about") @PathParam("resourceId") String resourceId,
									 @ApiParam(value="Key-value map, key is identifier for resource, value is token for resource") Map<String, String> resourceCredentials) {
		System.out.println("Resource info requested for : " + resourceId);
		return infoService.info(UUID.fromString(resourceId), resourceCredentials);
	}
	
	@GET
	@Path("/info/resources")
	@ApiOperation(value = "Returns list of resources available")
	public List<Resource> resources(){
		return infoService.resources();
	}
	
	@POST
	@Path("/search/{resourceId}")
	@ApiOperation(value = "Searches for paths on the given resource matching the supplied search term")
	public SearchResults search(@ApiParam(value="The UUID of the resource to search") @PathParam("resourceId") UUID resourceId,
								@ApiParam(value="Object containing credentials map under 'resourceCredentials' " +
										"and search term under 'query'") QueryRequest searchQueryRequest) {
		return searchService.search(resourceId, searchQueryRequest);
	}
	
	@POST
	@Path("/query/{resourceId}")
	@ApiOperation(value = "Submits a query to the given resource")
	public QueryStatus query(@ApiParam(value="The UUID of the resource to query") @PathParam("resourceId") UUID resourceId,
							 @ApiParam(value="Object containing credentials map under 'resourceCredentials' " +
									 "and query object under 'query'")QueryRequest dataQueryRequest) {
		return queryService.query(resourceId, dataQueryRequest);
	}
	
	@POST
	@Path("/query/{queryId}/status")
	@ApiOperation(value = "Returns the status of the given query")
	public QueryStatus queryStatus(@ApiParam(value="The UUID of the query to fetch the status of") @PathParam("queryId") UUID queryId,
								   @ApiParam(value="Key-value map, key is identifier for resource, value is token for resource") Map<String, String> resourceCredentials) {
		return queryService.queryStatus(queryId, resourceCredentials);
	}
	
	@POST
	@Path("/query/{queryId}/result")
	@ApiOperation(value = "Returns result for given query")
	public Response queryResult(@ApiParam(value="The UUID of the query to fetch the results of") @PathParam("queryId") UUID queryId,
								@ApiParam(value="Key-value map, key is identifier for resource, value is token for resource") Map<String, String> resourceCredentials) {
		return queryService.queryResult(queryId, resourceCredentials);
	}

	@GET
	@Path("/query/{queryId}/metadata")
	public QueryStatus queryMetdata(@PathParam("queryId") UUID queryId){
		return queryService.queryMetadata(queryId);
	}
	
}
