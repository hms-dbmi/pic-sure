package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.util.PicsureNaming;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.service.BaseEntityService;
import edu.harvard.hms.dbmi.avillach.auth.service.TermsOfServiceService;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.util.UUID;

@Path("tos")
public class TermsOfServiceWebService extends BaseEntityService<TermsOfService> {

    public TermsOfServiceWebService() {
        super(TermsOfService.class);
    }

    @Inject
    TermsOfServiceService tosService;

    @Path("/latest")
    @GET
    @Produces("text/html")
    public Response getLatestTermsOfService(){
        return Response.ok(tosService.getLatest()).build();
    }

    @POST
    @RolesAllowed(PicsureNaming.RoleNaming.ROLE_SYSTEM)
    @Consumes("text/html")
    @Produces("application/json")
    public Response updateTermsOfService(String html){
        return Response.status(201).entity(tosService.updateTermsOfService(html)).build();
    }

    @Path("/")
    @GET
    @Produces("text/plain")
    public Response hasUserAcceptedTOS(@Context SecurityContext securityContext){
        String userSubject = securityContext.getUserPrincipal().getName();
        return Response.ok(tosService.hasUserAcceptedLatest(userSubject)).build();
    }

    @Path("/accept")
    @POST
    @Produces("application/json")
    public Response acceptTermsOfService(@Context SecurityContext securityContext){
        String userSubject = securityContext.getUserPrincipal().getName();
        tosService.acceptTermsOfService(userSubject);
        return Response.ok().build();
    }

}
