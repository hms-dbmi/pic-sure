package edu.harvard.dbmi.avillach;

import java.util.Map;
import java.util.Optional;
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
									 @ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
											 "key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest,
									 @ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		System.out.println("Resource info requested for : " + resourceId);
		return infoService.info(UUID.fromString(resourceId), credentialsQueryRequest, resourceUUID);
	}
	
	@GET
	@Path("/info/resources")
	@ApiOperation(value = "Returns list of resources available")
	public Map<UUID,String> resources(
			@ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return infoService.resources(resourceUUID);
	}
	
	@POST
	@Path("/search/{resourceId}")
	@ApiOperation(value = "Searches for paths on the given resource matching the supplied search term")
	public SearchResults search(@ApiParam(value="The UUID of the resource to search") @PathParam("resourceId") UUID resourceId,
								@ApiParam(value="Object containing credentials map under 'resourceCredentials' " +
										"and search term under 'query'") QueryRequest searchQueryRequest,
								@ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return searchService.search(resourceId, searchQueryRequest, resourceUUID);
	}
	
	@POST
	@Path("/query")
	@ApiOperation(value = "Submits a query to the given resource")
	public QueryStatus query(@ApiParam(value="Object containing credentials map under 'resourceCredentials' " +
									 "and query object under 'query'")QueryRequest dataQueryRequest,
							 @ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return queryService.query(dataQueryRequest, resourceUUID);
	}
	
	@POST
	@Path("/query/{queryId}/status")
	@ApiOperation(value = "Returns the status of the given query")
	public QueryStatus queryStatus(@ApiParam(value="The UUID of the query to fetch the status of") @PathParam("queryId") UUID queryId,
								   @ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
										   "key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest,
								   @ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return queryService.queryStatus(queryId, credentialsQueryRequest, resourceUUID);
	}
	
	@POST
	@Path("/query/{queryId}/result")
	@ApiOperation(value = "Returns result for given query")
	public Response queryResult(@ApiParam(value="The UUID of the query to fetch the results of") @PathParam("queryId") UUID queryId,
								@ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
										"key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest,
								@ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return queryService.queryResult(queryId, credentialsQueryRequest, resourceUUID);
	}

	@POST
	@Path("/query/sync")
	@ApiOperation(value = "Returns result for given query")
	public Response querySync(@ApiParam(value="Object with field named 'resourceCredentials' which is a key-value map, " +
										"key is identifier for resource, value is token for resource") QueryRequest credentialsQueryRequest,
							  @ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return queryService.querySync(credentialsQueryRequest, resourceUUID);
	}
	
	@GET
	@Path("/query/{queryId}/metadata")
	public QueryStatus queryMetadata(@PathParam("queryId") UUID queryId,
									 @ApiParam(value = "Optional: resource UUID of Open or Authorized Access. Utilized for logging purposes.") Optional<String> resourceUUID) {
		return queryService.queryMetadata(queryId, resourceUUID);
	}
	
}
