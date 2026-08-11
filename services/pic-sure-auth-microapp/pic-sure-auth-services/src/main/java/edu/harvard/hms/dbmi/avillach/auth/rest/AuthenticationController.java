package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.request.AuthenticationRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.AuthenticationResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;


/**
 * <p>The authentication endpoint for PSAMA.</p>
 */
@Tag(name = "Authentication")
@RestController
@RequestMapping("/")
public class AuthenticationController {

    private final static Logger logger = LoggerFactory.getLogger(AuthenticationController.class.getName());

    private final AuthenticationServiceRegistry authenticationServiceRegistry;
    private final SessionService sessionService;

    @Autowired
    public AuthenticationController(AuthenticationServiceRegistry authenticationServiceRegistry, SessionService sessionService) {
        this.authenticationServiceRegistry = authenticationServiceRegistry;
        this.sessionService = sessionService;
    }

    /**
     * Every failure answers the uniform error contract. A denial that reaches the user as a login-page message must still carry text, which
     * is why {@code message} stays populated with the same wording the {@code PicSureResponseBody} envelope used to put in {@code content}.
     */
    @Operation(description = "The authentication endpoint for retrieving a valid user token")
    @AuditEvent(type = "AUTH", action = "auth.login")
    @PostMapping(path = "/authentication/{idpProvider}", consumes = "application/json", produces = "application/json")
    public AuthenticationResponse authentication(
        @PathVariable("idpProvider") String idpProvider,
        @Parameter(
            required = true,
            description = "The credentials the identity provider needs: an OIDC code, or an Auth0 access token plus " + "redirect URI"
        ) @RequestBody AuthenticationRequest authRequest, HttpServletRequest request
    ) throws IOException {
        logger.debug("authentication() starting...");
        logger.debug("authentication() requestHost: {}", request.getServerName());

        AuditAttributes.putMetadata(request, "idp", idpProvider);

        if (authRequest == null) {
            logger.error("authentication() authRequest is null");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "null_request");
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "authRequest is null");
        }

        AuthenticationService authenticationService = authenticationServiceRegistry.getAuthenticationService(idpProvider);
        if (authenticationService == null) {
            logger.error("authentication() no authentication service is registered for idp {}", idpProvider);
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "unknown_idp");
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "No authentication service for idp: " + idpProvider);
        }

        AuthenticationResponse authenticated = authenticationService.authenticate(authRequest, request.getServerName());
        if (authenticated == null) {
            logger.error("authentication() User not authenticated.");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "authentication_failed");
            throw new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", "User not authenticated.");
        }

        if (authenticated.userId() == null) {
            logger.error("authentication() User claims must contain a userId to start their session.");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "missing_user_id");
            throw new PicsureException(HttpStatus.UNAUTHORIZED, "unauthorized", "User not authenticated.");
        }

        sessionService.startSession(authenticated.userId());
        logger.info("authentication() User authenticated successfully.");
        AuditAttributes.putMetadata(request, "login_result", "success");
        AuditAttributes.putMetadata(request, "user_id", authenticated.userId());
        return authenticated;
    }
}
