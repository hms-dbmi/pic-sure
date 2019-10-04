package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.service.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

@Path("tos")
public class TermsOfServiceEndpoint extends BaseEntityService<TermsOfService> {

    public TermsOfServiceEndpoint() {
        super(TermsOfService.class);
    }

    @Inject
    TOSService tosService;

    @ApiOperation(value = "GET the latest Terms of Service")
    @Path("/latest")
    @GET
    @Produces("text/html")
    public Response getLatestTermsOfService(){
        return Response.ok(tosService.getLatest()).build();
    }

    @ApiOperation(value = "Update the Terms of Service html body")
    @POST
    @RolesAllowed({AuthNaming.AuthRoleNaming.ADMIN, SUPER_ADMIN})
    @Consumes("text/html")
    @Produces("application/json")
    public Response updateTermsOfService(
            @ApiParam(required = true, value = "A html page for updating") String html){
        return Response.status(201).entity(tosService.updateTermsOfService(html)).build();
    }

    @ApiOperation(value = "GET if current user has acceptted his TOS or not")
    @Path("/")
    @GET
    @Produces("text/plain")
    public Response hasUserAcceptedTOS(@Context SecurityContext securityContext){
        String userSubject = securityContext.getUserPrincipal().getName();
        return Response.ok(tosService.hasUserAcceptedLatest(userSubject)).build();
    }

    @ApiOperation(value = "Endpoint for current user to accept his terms of service")
    @Path("/accept")
    @POST
    @Produces("application/json")
    public Response acceptTermsOfService(@Context SecurityContext securityContext){
        String userSubject = securityContext.getUserPrincipal().getName();
        tosService.acceptTermsOfService(userSubject);
        return Response.ok().build();
    }

}
