package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.Map;

/**
 * <p>The authentication endpoint for PSAMA.</p>
 */
@Path("/")
@Consumes("application/json")
@Produces("application/json")
public class AuthService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuthenticationService authenticationService;

    @POST
    @Path("/authentication")
    public Response authentication(Map<String, String> authRequest){
        return authenticationService.getToken(authRequest);
    }
}
