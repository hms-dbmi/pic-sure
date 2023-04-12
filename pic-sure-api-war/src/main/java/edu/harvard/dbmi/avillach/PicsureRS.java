package edu.harvard.dbmi.avillach;

import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;

import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.PicsureInfoService;
import edu.harvard.dbmi.avillach.service.PicsureQueryService;
import edu.harvard.dbmi.avillach.service.PicsureSearchService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;

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
//	@ApiOperation(value = "Returns information about the provided resource")
//	@ApiResponse(code = 200, message = "Resource information", response = ResourceInfo.class)
	@Operation(summary = "Returns information about the provided resource",
			description = "Returns information about the provided resource",
			tags = { "info" },
			operationId = "resourceInfo",
			responses = { @io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Resource information",
					content = @io.swagger.v3.oas.annotations.media.Content(
							schema = @io.swagger.v3.oas.annotations.media.Schema(
									implementation = ResourceInfo.class
							)
					)
			)}
	)
	public ResourceInfo resourceInfo(@ApiParam(value="The UUID of the resource to fetch information about") @PathParam("resourceId") String resourceId,
									 @ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
											 "key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest) {
		System.out.println("Resource info requested for : " + resourceId);
		return infoService.info(UUID.fromString(resourceId), credentialsQueryRequest);
	}
	
	@GET
	@Path("/info/resources")
	@ApiOperation(value = "Returns list of resources available")
	@ApiResponse(code = 200, message = "Resource information", response = Map.class, responseContainer = "Map<UUID,String>")
	public Map<UUID,String> resources(){
		return infoService.resources();
	}
	
	@POST
	@Path("/search/{resourceId}")
	@ApiOperation(value = "Searches for paths on the given resource matching the supplied search term")
	@ApiResponse(code = 200, message = "Search results", response = SearchResults.class)
	public SearchResults search(@ApiParam(value="The UUID of the resource to search") @PathParam("resourceId") UUID resourceId,
								@ApiParam(value="Object containing credentials map under 'resourceCredentials' " +
										"and search term under 'query'") QueryRequest searchQueryRequest) {
		return searchService.search(resourceId, searchQueryRequest);
	}
	
	@POST
	@Path("/query")
	@ApiOperation(value = "Submits a query to the given resource")
	@ApiResponse(code = 200, message = "Query status", response = QueryStatus.class)
	public QueryStatus query(@ApiParam(value="Object containing credentials map under 'resourceCredentials' " +
									 "and query object under 'query'")QueryRequest dataQueryRequest) {
		return queryService.query(dataQueryRequest);
	}
	
	@POST
	@Path("/query/{queryId}/status")
	@ApiOperation(value = "Returns the status of the given query")
	@ApiResponse(code = 200, message = "Query status", response = QueryStatus.class)
	public QueryStatus queryStatus(@ApiParam(value="The UUID of the query to fetch the status of") @PathParam("queryId") UUID queryId,
								   @ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
										   "key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest) {
		return queryService.queryStatus(queryId, credentialsQueryRequest);
	}
	
	@POST
	@Path("/query/{queryId}/result")
	@ApiOperation(value = "Returns result for given query")
	@ApiResponse(code = 200, message = "Query result", response = Response.class)
	public Response queryResult(@ApiParam(value="The UUID of the query to fetch the results of") @PathParam("queryId") UUID queryId,
								@ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
										"key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest) {
		return queryService.queryResult(queryId, credentialsQueryRequest);
	}

	@POST
	@Path("/query/sync")
	@ApiOperation(value = "Returns result for given query")
	@ApiResponse(code = 200, message = "Query result", response = Response.class)
	public Response querySync(@ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
										"key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest) {
		return queryService.querySync(credentialsQueryRequest);
	}
	
	@GET
	@Path("/query/{queryId}/metadata")
	@ApiOperation(value = "Returns metadata for given query")
	@ApiResponse(code = 200, message = "Query metadata", response = QueryStatus.class)
	public QueryStatus queryMetadata(@PathParam("queryId") UUID queryId){
		return queryService.queryMetadata(queryId);
	}
	
}
