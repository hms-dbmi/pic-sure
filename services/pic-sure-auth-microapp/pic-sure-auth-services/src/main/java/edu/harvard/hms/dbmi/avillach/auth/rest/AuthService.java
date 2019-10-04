package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthorizationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.Map;

/**
 * <p>The authentication endpoint for PSAMA.</p>
 */
@Api
@Path("/")
@Consumes("application/json")
@Produces("application/json")
public class AuthService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuthenticationService authenticationService;

    @ApiOperation(value = "The authentication endpoint for retrieving a valid user token")
    @POST
    @Path("/authentication")
    public Response authentication(
            @ApiParam(required = true,
                    value = "A json object that includes all Oauth authentication needs, for example, " +
                            "access_token and redirectURI")
            Map<String, String> authRequest){
        return authenticationService.getToken(authRequest);
    }
}
