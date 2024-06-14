package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.OpenAuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

/**
 * <p>The authentication endpoint for PSAMA.</p>
 */
@Tag(name = "Open Authentication")
@RequestMapping("/open")
@Controller
public class OpenAuthenticationController {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final OpenAuthenticationService openAuthenticationService;
    private final String idp_provider;

    @Autowired
    public OpenAuthenticationController(OpenAuthenticationService openAuthenticationService, @Value("${application.idp.provider}") String idp_provider) {
        this.openAuthenticationService = openAuthenticationService;
        this.idp_provider = idp_provider;
    }

    @Operation(summary = "Authenticate a user using the open endpoint")
    @PostMapping(value = "/authentication", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> authentication(@Parameter(required = true, description = "A json object that includes all Oauth authentication needs, for example, access_token and redirectURI") Map<String, String> authRequest) {
        logger.debug("authentication() starting...");
        if (!this.idp_provider.equalsIgnoreCase("fence")) {
            Map<String, String> authenticate = openAuthenticationService.authenticate(authRequest);
            return PICSUREResponse.success(authenticate);
        }

        return PICSUREResponse.unauthorizedError("Not authorized.");
    }

}
