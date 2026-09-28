package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.auth.model.response.OpenSessionResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService.IssuedSession;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Issues open-access sessions to anonymous browsers. Public and ungated. The token is stateless, so the {@code open_session.create} audit
 * event is the only record that a session was issued.
 */
@Tag(name = "Open access", description = "Open-access sessions for anonymous browsers, and request validation called by the gateway")
@Controller
public class OpenSessionController {

    private final OpenSessionService openSessionService;
    private final boolean openIdpProviderIsEnabled;

    @Autowired
    public OpenSessionController(
        OpenSessionService openSessionService, @Value("${open.idp.provider.is.enabled}") boolean openIdpProviderIsEnabled
    ) {
        this.openSessionService = openSessionService;
        this.openIdpProviderIsEnabled = openIdpProviderIsEnabled;
    }

    @Operation(
        summary = "Start an open-access session",
        description = "Issue a short-lived open-access session token for an anonymous browser. Public endpoint. Send the token as the "
            + "X-PICSURE-API-Key header; the gateway returns a replacement in X-PICSURE-Session-Refresh once it is half used."
    )
    @ApiResponse(responseCode = "200", description = "The session token and when it expires")
    @ApiResponse(responseCode = "404", description = "Open-access sessions are not enabled on this deployment")
    @AuditEvent(type = "ACCESS", action = "open_session.create")
    @PostMapping(produces = "application/json", path = "/open/session")
    public ResponseEntity<?> createSession(HttpServletRequest request) {
        // 404, so the browser reads "no sessions here, go keyless" the same way as a PSAMA that predates this endpoint
        if (!openSessionService.isEnabled() || !openIdpProviderIsEnabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Open-access sessions are not enabled on this deployment.");
        }

        IssuedSession session = openSessionService.issue();
        AuditAttributes.putMetadata(request, "open_session_id", session.sessionId());
        return PICSUREResponse.success(new OpenSessionResponse(session.token(), session.expiresAt()));
    }
}
