package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AuthenticationRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.AuthenticationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.util.HashMap;


/**
 * <p>The authentication endpoint for PSAMA.</p>
 */
@Tag(name = "Authentication", description = "Exchange an identity provider login for a PIC-SURE token")
@Controller
@RequestMapping("/")
public class AuthenticationController {

    private final static Logger logger = LoggerFactory.getLogger(AuthenticationController.class.getName());

    private final AuthenticationServiceRegistry authenticationServiceRegistry;

    @Autowired
    public AuthenticationController(AuthenticationServiceRegistry authenticationServiceRegistry) {
        this.authenticationServiceRegistry = authenticationServiceRegistry;
    }

    @Operation(
        summary = "Exchange an identity provider's code for a PIC-SURE token",
        description = "The authentication endpoint for retrieving a valid user token"
    )
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "A PIC-SURE token for the authenticated user"),
            @ApiResponse(responseCode = "400", description = "No enabled identity provider has that name"),
            @ApiResponse(responseCode = "401", description = "The identity provider rejected the code, or the code is malformed")}
    )
    @AuditEvent(type = "AUTH", action = "auth.login")
    @PostMapping(path = "/authentication/{idpProvider}", consumes = "application/json", produces = "application/json")
    public ResponseEntity<AuthenticationResponse> authentication(
        @PathVariable("idpProvider") String idpProvider,
        @Parameter(
            required = true,
            description = "A json object that includes all Oauth authentication needs, for example, access_token and redirectURI."
        ) @RequestBody AuthenticationRequest authRequest, HttpServletRequest request
    ) throws IOException {
        logger.debug("authentication() starting...");
        logger.debug("authentication() requestHost: {}", request.getServerName());

        AuditAttributes.putMetadata(request, "idp", idpProvider);

        if (authRequest == null) {
            logger.error("authentication() authRequest is null");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "null_request");
            throw new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "authRequest is null");
        }

        AuthenticationService authenticationService = authenticationServiceRegistry.getAuthenticationService(idpProvider);
        if (authenticationService == null) {
            logger.error("authentication() authenticationService is null");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "unknown_idp");
            throw new PicSureResponseException(HttpStatus.BAD_REQUEST, "Invalid request", "authenticationService is null");
        }

        HashMap<String, String> authenticate = authenticationService.authenticate(authRequest.toMap(), request.getServerName());
        if (CollectionUtils.isEmpty(authenticate)) {
            logger.error("authentication() User not authenticated.");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "authentication_failed");
            throw new PicSureResponseException(HttpStatus.UNAUTHORIZED, "Unauthorized", "User not authenticated.");
        }
        if (!authenticate.containsKey("userId")) {
            logger.error("Authentication response must contain a userId.");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "missing_user_id");
            throw new PicSureResponseException(HttpStatus.UNAUTHORIZED, "Unauthorized", "User not authenticated.");
        }

        logger.info("authentication() User authenticated successfully.");
        AuditAttributes.putMetadata(request, "login_result", "success");
        AuditAttributes.putMetadata(request, "user_id", authenticate.get("userId"));
        return PICSUREResponse.success(AuthenticationResponse.from(authenticate));
    }
}
