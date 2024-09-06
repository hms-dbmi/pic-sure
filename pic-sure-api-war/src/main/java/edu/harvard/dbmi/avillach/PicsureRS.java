package edu.harvard.dbmi.avillach;

import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

import edu.harvard.dbmi.avillach.data.entity.Query;
import edu.harvard.dbmi.avillach.domain.*;
import edu.harvard.dbmi.avillach.service.*;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OpenAPIDefinition(info = @Info(title = "Pic-sure API", version = "1.0.0", description = "This is the Pic-sure API."))
@Path("/")
@Produces("application/json")
@Consumes("application/json")
public class PicsureRS {

    private final Logger logger = LoggerFactory.getLogger(PicsureRS.class);

    @Inject
    PicsureInfoService infoService;

    @Inject
    PicsureSearchService searchService;

    @Inject
    PicsureQueryService queryService;

    @Inject
    FormatService formatService;

    @Inject
    ProxyWebClient proxyWebClient;

    @POST
    @Path("/info/{resourceId}")
    @Operation(
        summary = "Returns information about the provided resource", tags = {"info"}, operationId = "resourceInfo",
        responses = {@ApiResponse(
            responseCode = "200", description = "Resource information",
            content = @Content(schema = @Schema(implementation = ResourceInfo.class))
        )}
    )
    public ResourceInfo resourceInfo(
        @Parameter(description = "The UUID of the resource to fetch information about") @PathParam("resourceId") String resourceId,
        @Parameter QueryRequest credentialsQueryRequest, @Context HttpHeaders headers
    ) {
        System.out.println("Resource info requested for : " + resourceId);
        return infoService.info(UUID.fromString(resourceId), credentialsQueryRequest, headers);
    }

    @GET
    @Path("/info/resources")
    @Operation(
        summary = "Returns list of resources available",
        responses = {@ApiResponse(
            responseCode = "200", description = "Resource information", content = @Content(schema = @Schema(implementation = Map.class))
        )}
    )
    public Map<UUID, String> resources(@Context HttpHeaders headers) {
        return infoService.resources(headers);
    }

    @GET
    @Path("/search/{resourceId}/values/")
    @Consumes("*/*")
    public PaginatedSearchResult<?> searchGenomicConceptValues(
        @PathParam("resourceId") UUID resourceId, QueryRequest searchQueryRequest,
        @QueryParam("genomicConceptPath") String genomicConceptPath, @QueryParam("query") String query, @QueryParam("page") Integer page,
        @QueryParam("size") Integer size, @Context HttpHeaders headers
    ) {
        return searchService.searchGenomicConceptValues(resourceId, searchQueryRequest, genomicConceptPath, query, page, size, headers);
    }

    @POST
    @Path("/search/{resourceId}")
    @Operation(
        summary = "Searches for concept paths on the given resource matching the supplied search term",
        responses = {@ApiResponse(
            responseCode = "200", description = "Search results", content = @Content(schema = @Schema(implementation = SearchResults.class))
        )}, requestBody = @RequestBody(required = true, content = @Content(schema = @Schema(example = "{ \"query\": \"searchTerm\" }")))
    )
    public SearchResults search(
        @Parameter(description = "The UUID of the resource to search") @PathParam("resourceId") UUID resourceId,
        @Parameter(hidden = true) QueryRequest searchQueryRequest, @Context HttpHeaders headers
    ) {
        return searchService.search(resourceId, searchQueryRequest, headers);
    }

    @POST
    @Path("/query")
    @Operation(
        summary = "Submits a query to the given resource",
        responses = {@ApiResponse(
            responseCode = "200", description = "Query status", content = @Content(schema = @Schema(implementation = QueryStatus.class))
        )}
    )
    public QueryStatus query(
        @Parameter QueryRequest dataQueryRequest,

        @Context HttpHeaders headers,

        @Parameter @QueryParam("isInstitute") Boolean isInstitutionQuery,

        @Context SecurityContext context
    ) {
        if (isInstitutionQuery == null || !isInstitutionQuery) {
            return queryService.query(dataQueryRequest, headers);
        } else {
            String email = context.getUserPrincipal().getName();
            return queryService.institutionalQuery((FederatedQueryRequest) dataQueryRequest, headers, email);
        }
    }

    @POST
    @Path("/query/{queryId}/status")
    @Operation(
        summary = "Returns the status of the given query",
        responses = {@ApiResponse(
            responseCode = "200", description = "Query status", content = @Content(schema = @Schema(implementation = QueryStatus.class))
        )}
    )
    public QueryStatus queryStatus(
        @Parameter(
            description = "The UUID of the query to fetch the status of. The UUID is returned by the /query "
                + "endpoint as the \"picsureResultId\" in the response object"
        ) @PathParam("queryId") UUID queryId,

        @Parameter QueryRequest credentialsQueryRequest,

        @Context HttpHeaders headers,

        @Parameter @QueryParam("isInstitute") Boolean isInstitutionQuery
    ) {
        if (credentialsQueryRequest instanceof GeneralQueryRequest) {
            return queryService.queryStatus(queryId, (GeneralQueryRequest) credentialsQueryRequest, headers);
        } else {
            return queryService.institutionQueryStatus(queryId, (FederatedQueryRequest) credentialsQueryRequest, headers);
        }
    }

    @POST
    @Path("/query/{queryId}/result")
    @Operation(
            summary = "Returns result for given query",
            responses = {@ApiResponse(
                    responseCode = "200", description = "Query result", content = @Content(schema = @Schema(implementation = Response.class))
            )}
    )
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response queryResult(
            @Parameter(
                    description = "The UUID of the query to fetch the status of. The UUID is "
                            + "returned by the /query endpoint as the \"picsureResultId\" in the response object"
            ) @PathParam("queryId") UUID queryId, @Parameter QueryRequest credentialsQueryRequest, @Context HttpHeaders headers
    ) {
        return queryService.queryResult(queryId, credentialsQueryRequest, headers);
    }
    @POST
    @Path("/query/{queryId}/signed-url")
    @Operation(
            summary = "Returns a signed url for given query",
            responses = {@ApiResponse(
                    responseCode = "200", description = "Query result", content = @Content(schema = @Schema(implementation = Response.class))
            )}
    )
    @Produces(MediaType.APPLICATION_JSON)
    public Response queryResultSignedUrl(
            @Parameter(
                    description = "The UUID of the query to fetch the status of. The UUID is "
                            + "returned by the /query endpoint as the \"picsureResultId\" in the response object"
            ) @PathParam("queryId") UUID queryId, @Parameter QueryRequest credentialsQueryRequest, @Context HttpHeaders headers
    ) {
        return queryService.queryResultSignedUrl(queryId, credentialsQueryRequest, headers);
    }

    @POST
    @Path("/query/sync")
    @Operation(
        summary = "Returns result for given query",
        responses = {@ApiResponse(
            responseCode = "200", description = "Query result", content = @Content(schema = @Schema(implementation = Response.class))
        )}
    )
    public Response querySync(
        @Context HttpHeaders headers,
        @Parameter(
            description = "Object with field named 'resourceCredentials' which is a key-value map, "
                + "key is identifier for resource, value is token for resource"
        ) QueryRequest credentialsQueryRequest
    ) {
        return queryService.querySync(credentialsQueryRequest, headers);
    }

    @GET
    @Path("/query/{queryId}/metadata")
    @Operation(
        summary = "Returns metadata for given query",
        description = "Generally used to reconstruct a query that was previously submitted.	The queryId is "
            + "returned by the /query endpoint as the \"picsureResultId\" in the response object",
        responses = {@ApiResponse(
            responseCode = "200", description = "Query metadata", content = @Content(schema = @Schema(implementation = QueryStatus.class))
        )}
    )
    public QueryStatus queryMetadata(@PathParam("queryId") UUID queryId, @Context HttpHeaders headers) {
        return queryService.queryMetadata(queryId, headers);
    }

    @POST
    @Path("/bin/continuous")
    public Response generateContinuousBin(QueryRequest continuousData, @Context HttpHeaders headers) {
        return formatService.format(continuousData, headers);
    }


    @POST
    @Path("/proxy/{container}/{request : .+}")
    @Operation(hidden = true)
    public Response postProxy(
        @PathParam("container") String containerId, @PathParam("request") String request, @Context UriInfo uriInfo, String body
    ) {
        return proxyWebClient.postProxy(containerId, request, body, uriInfo.getQueryParameters());
    }

    @GET
    @Path("/proxy/{container}/{request : .+}")
    @Operation(hidden = true)
    public Response getProxy(@PathParam("container") String containerId, @PathParam("request") String request, @Context UriInfo uriInfo) {
        return proxyWebClient.getProxy(containerId, request, uriInfo.getQueryParameters());
    }

}
