package edu.harvard.dbmi.avillach;

import java.util.UUID;

import javax.inject.Inject;

import javax.validation.Valid;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import edu.harvard.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.dbmi.avillach.data.request.NamedDatasetRequest;
import edu.harvard.dbmi.avillach.service.NamedDatasetService;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;

@Path("/dataset/named")
@Produces("application/json")
@Consumes("application/json")
public class NamedDatasetRS {
    @Inject
    NamedDatasetService namedDatasetService;

    @GET
    @Path("/")
    @Operation(
        summary = "Returns a list of named datasets saved by the authenticated user.",
        tags = { "dataset" },
        operationId = "namedDataset",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "A list of named datasets saved by the authenticated user.",
                content = @Content(
                    schema = @Schema(
                        implementation = NamedDataset.class
                    )
                )
            ),
            @ApiResponse(
                responseCode = "500",
                description = "Error finding any named datasets for user.",
                content = @Content(
                    examples = {@ExampleObject(
                        name = "namedDatasets",
                        value = "{\"errorType\":\"error\",\"message\":\"Could not retrieve named datasets\"}"
                    )}
                )
            )
        }
    )
    public Response namedDatasets(
        @Context SecurityContext context
    ) {
        String user = context.getUserPrincipal().getName();
        return namedDatasetService.getNamedDatasets(user)
            .map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not retrieve named datasets"));
    }

    @POST
    @Path("/")
    @Operation(
        summary = "Returns a named dataset saved by the authenticated user.",
        tags = { "dataset" },
        operationId = "addNamedDataset",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "The named dataset saved by the authenticated user.",
                content = @Content(
                    schema = @Schema(
                        implementation = NamedDataset.class
                    )
                )
            ),
            @ApiResponse(
                responseCode = "500",
                description = "Error adding any named datasets.",
                content = @Content(
                    examples = {@ExampleObject(
                        name = "error",
                        value = "{\"errorType\":\"error\",\"message\":\"Could not save named dataset\"}"
                    )}
                )
            )
        }
    )
    public Response addNamedDataset(
        @Context SecurityContext context,
        @Parameter @Valid NamedDatasetRequest request
    ) {
        String user = context.getUserPrincipal().getName();
        return namedDatasetService.addNamedDataset(user, request)
            .map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not save named dataset"));
    }

    @GET
    @Path("/{namedDatasetId}/")
    @Operation(
        summary = "Returns a named dataset requested by the authenticated user.",
        tags = { "dataset" },
        operationId = "getNamedDatasetById",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "The named dataset requested by the authenticated user.",
                content = @Content(
                    schema = @Schema(
                        implementation = NamedDataset.class
                    )
                )
            ),
            @ApiResponse(
                responseCode = "500",
                description = "Error finding the named dataset.",
                content = @Content(
                    examples = {@ExampleObject(
                        name = "error",
                        value = "{\"errorType\":\"error\",\"message\":\"Could not retrieve named dataset\"}"
                    )}
                )
            )
        }
    )
    public Response getNamedDatasetById(
        @Context SecurityContext context,
        @PathParam("namedDatasetId") UUID datasetId
    ){
        String user = context.getUserPrincipal().getName();
        return namedDatasetService.getNamedDatasetById(user, datasetId)
            .map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not retrieve named dataset"));
    }

    @PUT
    @Path("/{namedDatasetId}/")
    @Operation(
        summary = "Updates a named dataset that the authenticated user perviously saved.",
        tags = { "dataset" },
        operationId = "updateNamedDataset",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "The named dataset updated by the authenticated user.",
                content = @Content(
                    schema = @Schema(
                        implementation = NamedDataset.class
                    )
                )
            ),
            @ApiResponse(
                responseCode = "500",
                description = "Error updating the named dataset.",
                content = @Content(
                    examples = {@ExampleObject(
                        name = "error",
                        value = "{\"errorType\":\"error\",\"message\":\"Could not update named dataset\"}"
                    )}
                )
            )
        }
    )
    public Response updateNamedDataset(
        @Context SecurityContext context,
        @PathParam("namedDatasetId") UUID datasetId,
        @Parameter @Valid NamedDatasetRequest request
    ){
        String user = context.getUserPrincipal().getName();
        return namedDatasetService.updateNamedDataset(user, datasetId, request)
            .map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not update named dataset"));
    }
}
