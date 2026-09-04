package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.util.Date;
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
    private final SessionService sessionService;
    private final JWTUtil jwtUtil;

    @Autowired
    public AuthenticationController(
        AuthenticationServiceRegistry authenticationServiceRegistry, SessionService sessionService, JWTUtil jwtUtil
    ) {
        this.authenticationServiceRegistry = authenticationServiceRegistry;
        this.sessionService = sessionService;
        this.jwtUtil = jwtUtil;
    }

    @Operation(description = "The authentication endpoint for retrieving a valid user token")
    @AuditEvent(type = "AUTH", action = "auth.login")
    @PostMapping(path = "/authentication/{idpProvider}", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> authentication(
            @PathVariable("idpProvider") String idpProvider,
            @Parameter(required = true, description = "A json object that includes all Oauth authentication needs, for example, access_token and redirectURI")
            @RequestBody Map<String, String> authRequest, HttpServletRequest request) throws IOException {
        logger.debug("authentication() starting...");
        logger.debug("authentication() requestHost: {}", request.getServerName());

        AuditAttributes.putMetadata(request, "idp", idpProvider);

        if (authRequest == null) {
            logger.error("authentication() authRequest is null");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "null_request");
            return ResponseEntity.badRequest().body("authRequest is null");
        }

        AuthenticationService authenticationService = authenticationServiceRegistry.getAuthenticationService(idpProvider);
        if (authenticationService == null) {
            logger.error("authentication() authenticationService is null");
            AuditAttributes.putMetadata(request, "login_result", "failure");
            AuditAttributes.putMetadata(request, "reason", "unknown_idp");
            return ResponseEntity.badRequest().body("authenticationService is null");
        }

        long loginStartedAt = System.currentTimeMillis();
        HashMap<String, String> authenticate = authenticationService.authenticate(authRequest, request.getServerName());
        if (!CollectionUtils.isEmpty(authenticate)) {
            if (authenticate.containsKey("userId")) {
                sessionService.startSession(authenticate.get("userId"), issuedAtOf(authenticate.get("token"), loginStartedAt));
            } else {
                logger.error("authentication() userId authentication is null");
                logger.error("User claims must contain a userId to start their session.");
                AuditAttributes.putMetadata(request, "login_result", "failure");
                AuditAttributes.putMetadata(request, "reason", "missing_user_id");
                return PICSUREResponse.unauthorizedError("User not authenticated.");
            }
            logger.info("authentication() User authenticated successfully.");
            AuditAttributes.putMetadata(request, "login_result", "success");
            AuditAttributes.putMetadata(request, "user_id", authenticate.get("userId"));
            return PICSUREResponse.success(authenticate);
        }

        logger.error("authentication() User not authenticated.");
        AuditAttributes.putMetadata(request, "login_result", "failure");
        AuditAttributes.putMetadata(request, "reason", "authentication_failed");
        return PICSUREResponse.unauthorizedError("User not authenticated.");
    }

    /**
     * Use the token's own {@code iat} as the session start. JWT timestamps have second precision, so anchoring to the
     * wall clock instead would leave the token that just opened the session looking older than it. If the issued-at
     * cannot be read, fall back to the second the login began, which no token minted during it can precede.
     */
    private Date issuedAtOf(String token, long loginStartedAt) {
        Date loginStartedAtSecond = new Date(loginStartedAt / 1000 * 1000);
        if (token == null) {
            return loginStartedAtSecond;
        }

        try {
            Date issuedAt = this.jwtUtil.parseToken(token).getPayload().getIssuedAt();
            return issuedAt != null ? issuedAt : loginStartedAtSecond;
        } catch (Exception e) {
            logger.warn("authentication() Could not read the issued-at claim of the token just minted: {}", e.getMessage());
            return loginStartedAtSecond;
        }
    }
}
