package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.TermsOfServiceResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Optional;


/**
 * <p>Endpoint for creating and updating terms of service entities. Records when a user accepts a term of service.</p>
 */
@Tag(name = "Terms of Service Management", description = "Terms of service text and acceptance")
@Controller
@RequestMapping("/tos")
public class TermsOfServiceController {

    private final Logger logger = LoggerFactory.getLogger(TermsOfServiceController.class);
    private final TOSService tosService;
    private final UserService userService;

    @Autowired
    public TermsOfServiceController(TOSService tosService, UserService userService) {
        this.tosService = tosService;
        this.userService = userService;
    }

    @Operation(summary = "The current terms of service as HTML", description = "GET the latest Terms of Service")
    @ApiResponse(
        responseCode = "200", description = "The current terms of service as HTML, or an empty body when none are stored",
        content = @Content(
            mediaType = "text/html",
            schema = @Schema(
                type = "string", description = "The current terms of service as an HTML fragment.",
                example = "<h1>Terms of Service</h1><p>Use of this system is monitored.</p>"
            )
        )
    )
    @AuditEvent(type = "ACCESS", action = "tos.view")
    @GetMapping(path = "/latest", produces = "text/html")
    public ResponseEntity<String> getLatestTermsOfService() {
        logger.info("Getting latest Terms of Service");
        return PICSUREResponse.success(tosService.getLatest());
    }

    @Operation(summary = "Replace the terms of service", description = "Update the Terms of Service html body")
    @ApiResponse(
        responseCode = "200", description = "The stored terms of service, or an empty body when the store could not read them back"
    )
    @AuditEvent(type = "ADMIN", action = "tos.update")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @PostMapping(path = "/update", consumes = "text/html", produces = "application/json")
    public ResponseEntity<TermsOfServiceResponse> updateTermsOfService(@RequestBody String html, HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.getContext();
        CustomUserDetails customUserDetails = (CustomUserDetails) context.getAuthentication().getPrincipal();
        String userSubject = customUserDetails.getUser().getSubject();
        logger.info("User {} updating TOS", userSubject);
        Optional<TermsOfService> termsOfService = tosService.updateTermsOfService(html);
        if (termsOfService.isEmpty()) {
            return ResponseEntity.ok().build();
        }
        User user = tosService.acceptTermsOfService(userSubject);
        userService.updateUser(List.of(user));
        AuditAttributes.putMetadata(request, "tos_updated", "true");
        return PICSUREResponse.success(TermsOfServiceResponse.from(termsOfService.get()));
    }

    @Operation(
        summary = "Whether the caller has accepted the current terms", description = "GET if current user has acceptted his TOS or not"
    )
    @ApiResponse(
        responseCode = "200", description = "True when the caller has accepted the current terms",
        content = @Content(
            mediaType = "text/plain",
            schema = @Schema(type = "boolean", description = "True when the caller has accepted the current terms of service.")
        )
    )
    @AuditEvent(type = "ACCESS", action = "tos.view")
    @GetMapping(produces = "text/plain")
    public ResponseEntity<Boolean> hasUserAcceptedTOS() {
        SecurityContext context = SecurityContextHolder.getContext();
        CustomUserDetails customUserDetails = (CustomUserDetails) context.getAuthentication().getPrincipal();
        String userSubject = customUserDetails.getUser().getSubject();
        logger.info("hasUserAcceptedTOS for user {}", userSubject);
        return PICSUREResponse.success(tosService.hasUserAcceptedLatest(userSubject));
    }

    @Operation(
        summary = "Accept the current terms for the caller", description = "Endpoint for current user to accept his terms of service"
    )
    @ApiResponse(responseCode = "200", description = "The terms were accepted; the response has no body")
    @AuditEvent(type = "ACCESS", action = "tos.accept")
    @PostMapping(path = "/accept", produces = "application/json")
    public ResponseEntity<Void> acceptTermsOfService(HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.getContext();
        CustomUserDetails customUserDetails = (CustomUserDetails) context.getAuthentication().getPrincipal();
        String userSubject = customUserDetails.getUser().getSubject();
        User user = tosService.acceptTermsOfService(userSubject);
        userService.updateUser(List.of(user));
        AuditAttributes.putMetadata(request, "tos_accepted", "true");
        return ResponseEntity.ok().build();
    }

}
