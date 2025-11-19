package edu.harvard.dbmi.avillach;

import java.util.UUID;

import javax.inject.Inject;

import javax.validation.Valid;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import edu.harvard.dbmi.avillach.data.entity.Configuration;
import edu.harvard.dbmi.avillach.data.request.ConfigurationRequest;
import edu.harvard.dbmi.avillach.service.ConfigurationService;
import edu.harvard.dbmi.avillach.util.response.PICSUREResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;

@Path("/configuration")
@Produces("application/json")
@Consumes("application/json")
public class ConfigurationRS {
    @Inject
    ConfigurationService configurationService;

    @GET
    @Path("/")
    @Operation(
        summary = "Returns a list of all configurations.", tags = {"configuration"}, operationId = "getConfigurations",
        responses = {
            @ApiResponse(
                responseCode = "200", description = "A list of all configurations.",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = Configuration.class)))
            ),
            @ApiResponse(
                responseCode = "500", description = "Error finding configurations.",
                content = @Content(
                    examples = {@ExampleObject(
                        name = "error", value = "{\"errorType\":\"error\",\"message\":\"Could not retrieve configurations\"}"
                    )}
                )
            )}
    )
    public Response getConfigurations(@Context SecurityContext context, @QueryParam("kind") String kind) {
        return configurationService.getConfigurations(kind).map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not retrieve configurations"));
    }

    @GET
    @Path("/{configuration}/")
    @Operation(
        summary = "Returns a configuration by ID.", tags = {"configuration"}, operationId = "getConfigurationById",
        responses = {
            @ApiResponse(
                responseCode = "200", description = "The requested configuration.",
                content = @Content(schema = @Schema(implementation = Configuration.class))
            ),
            @ApiResponse(
                responseCode = "500", description = "Error finding the configuration.",
                content = @Content(
                    examples = {@ExampleObject(
                        name = "error", value = "{\"errorType\":\"error\",\"message\":\"Could not retrieve configuration\"}"
                    )}
                )
            )}
    )
    public Response getConfigurationById(@Context SecurityContext context, @PathParam("configuration") String identifier) {
        return configurationService.getConfigurationByIdentifier(identifier).map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not retrieve configuration"));
    }

    @POST
    @Path("/admin/")
    @Operation(
        summary = "Creates a new configuration.", tags = {"configuration"}, operationId = "addConfiguration",
        responses = {
            @ApiResponse(
                responseCode = "200", description = "The configuration created.",
                content = @Content(schema = @Schema(implementation = Configuration.class))
            ),
            @ApiResponse(
                responseCode = "500", description = "Error adding configuration.",
                content = @Content(
                    examples = {
                        @ExampleObject(name = "error", value = "{\"errorType\":\"error\",\"message\":\"Could not save configuration\"}")}
                )
            )}
    )
    public Response addConfiguration(@Context SecurityContext context, @Parameter @Valid ConfigurationRequest request) {
        if (request.getName() == null || request.getKind() == null || request.getValue() == null) {
            return PICSUREResponse.error("Name, Kind, and Value properties must not be null");
        }

        return configurationService.addConfiguration(request).map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not add configuration"));
    }

    @PATCH
    @Path("/admin/{configurationId}/")
    @Operation(
        summary = "Updates an existing configuration.", tags = {"configuration"}, operationId = "updateConfiguration",
        responses = {
            @ApiResponse(
                responseCode = "200", description = "The updated configuration.",
                content = @Content(schema = @Schema(implementation = Configuration.class))
            ),
            @ApiResponse(
                responseCode = "500", description = "Error updating the configuration.",
                content = @Content(
                    examples = {
                        @ExampleObject(name = "error", value = "{\"errorType\":\"error\",\"message\":\"Could not update configuration\"}")}
                )
            )}
    )
    public Response updateConfiguration(
        @Context SecurityContext context, @PathParam("configurationId") UUID configurationId, @Parameter @Valid ConfigurationRequest request
    ) {
        if (request.getUuid() != null && !configurationId.equals(request.getUuid())) {
            return PICSUREResponse.error("UUID cannot be changed");
        }

        request.setUuid(configurationId);
        return configurationService.updateConfiguration(request).map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not update configuration"));
    }

    @DELETE
    @Path("/admin/{configurationId}/")
    @Operation(
        summary = "Deletes a configuration.", tags = {"configuration"}, operationId = "deleteConfiguration",
        responses = {@ApiResponse(responseCode = "200", description = "Configuration successfully deleted."),
            @ApiResponse(
                responseCode = "500", description = "Error deleting the configuration.",
                content = @Content(
                    examples = {
                        @ExampleObject(name = "error", value = "{\"errorType\":\"error\",\"message\":\"Could not delete configuration\"}")}
                )
            )}
    )
    public Response deleteConfiguration(@Context SecurityContext context, @PathParam("configurationId") UUID configurationId) {
        return configurationService.deleteConfiguration(configurationId).map(PICSUREResponse::success)
            .orElse(PICSUREResponse.error("Could not delete configuration"));
    }
}
