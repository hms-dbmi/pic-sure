package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.FENCEAuthenticationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.util.Map;

@Path("/")
@Consumes("application/json")
@Produces("application/json")
public class AuthService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuthenticationService authenticationService;

    @Inject
    FENCEAuthenticationService fenceAuthenticationService;

    @POST
    @Path("/authentication")
    public Response authentication(Map<String, String> authRequest){
        logger.debug("authentication() starting...");
        if (JAXRSConfiguration.idp_provider.equalsIgnoreCase("fence")) {
            logger.debug("authentication() FENCE authentication");
            return fenceAuthenticationService.getFENCEProfile(authRequest);
        } else {
            logger.debug("authentication() default authentication");
            return authenticationService.getToken(authRequest);
        }
    }

}
