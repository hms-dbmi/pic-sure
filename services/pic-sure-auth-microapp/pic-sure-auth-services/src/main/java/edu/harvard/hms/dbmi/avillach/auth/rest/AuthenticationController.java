package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


/**
 * <p>The authentication endpoint for PSAMA.</p>
 */
@Tag(name = "Authentication")
@Controller
@RequestMapping("/")
public class AuthenticationController {

    private final static Logger logger = LoggerFactory.getLogger(AuthenticationController.class.getName());

    private final AuthenticationServiceRegistry authenticationServiceRegistry;

    @Autowired
    public AuthenticationController(AuthenticationServiceRegistry authenticationServiceRegistry) {
        this.authenticationServiceRegistry = authenticationServiceRegistry;
    }

    @Operation(description = "The authentication endpoint for retrieving a valid user token")
    @PostMapping(path = "/authentication/{idpProvider}", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> authentication(
            @PathVariable("idpProvider") String idpProvider,
            @Parameter(required = true, description = "A json object that includes all Oauth authentication needs, for example, access_token and redirectURI")
            @RequestBody Map<String, String> authRequest, HttpServletRequest request) throws IOException {
        logger.debug("authentication() starting...");
        logger.debug("authentication() requestHost: {}", request.getServerName());

        if (authRequest == null) {
            logger.error("authentication() authRequest is null");
            return ResponseEntity.badRequest().body("authRequest is null");
        }

        AuthenticationService authenticationService = authenticationServiceRegistry.getAuthenticationService(idpProvider);
        if (authenticationService == null) {
            logger.error("authentication() authenticationService is null");
            return ResponseEntity.badRequest().body("authenticationService is null");
        }

        HashMap<String, String> authenticate = authenticationService.authenticate(authRequest, request.getServerName());
        if (authenticate != null && !authenticate.isEmpty()) {
            logger.info("authentication() User authenticated successfully.");
            return PICSUREResponse.success(authenticate);
        }

        logger.error("authentication() User not authenticated.");
        return PICSUREResponse.unauthorizedError("User not authenticated.");
    }
}
