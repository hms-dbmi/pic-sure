package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationUpdateRequest;
import jakarta.validation.Valid;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for registering and administering applications. <br> Note: Only users with the super admin role can access this endpoint.</p>
 */
@Tag(name = "Application Management")
@Controller
@RequestMapping(value = "/application")
public class ApplicationController {

    private final ApplicationService applicationService;

    @Autowired
    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Operation(description = "GET information of one Application with the UUID, requires SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "application.read")
    @RolesAllowed({SUPER_ADMIN})
    @GetMapping(value = "/{applicationId}")
    public ResponseEntity<?> getApplicationById(
        @Parameter(required = true, description = "The UUID of the application to fetch information about") @PathVariable(
            "applicationId"
        ) String applicationId
    ) {
        Optional<Application> entityById = applicationService.getApplicationByID(applicationId);

        if (entityById.isEmpty()) {
            return PICSUREResponse.protocolError("Application is not found by given Application ID: " + applicationId);
        }

        return PICSUREResponse.success(Application.ApplicationForDisplay.from(entityById.get()));
    }

    @Operation(description = "GET a list of existing Applications, requires SUPER_ADMIN role")
    @AuditEvent(type = "OTHER", action = "application.list")
    @RolesAllowed({SUPER_ADMIN})
    @GetMapping
    public ResponseEntity<List<Application.ApplicationForDisplay>> getApplicationAll() {
        return PICSUREResponse
            .success(applicationService.getAllApplications().stream().map(Application.ApplicationForDisplay::from).toList());
    }

    @Operation(description = "POST a list of Applications, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Application>> addApplication(
        @Parameter(
            required = true, description = "A list of AccessRule in JSON format"
        ) @Valid @RequestBody List<ApplicationCreateRequest> requests, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(requests.size()));
        return PICSUREResponse.success(applicationService.createFrom(requests));
    }

    @Operation(description = "Update a list of Applications, will only update the fields listed, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Application>> updateApplication(
        @Parameter(
            required = true, description = "A list of AccessRule with fields to be updated in JSON format"
        ) @Valid @RequestBody List<ApplicationUpdateRequest> requests, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(requests.size()));
        return PICSUREResponse.success(applicationService.updateFrom(requests));
    }

    @Operation(description = "Refresh a token of an application by application Id, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.token_refresh")
    @RolesAllowed({SUPER_ADMIN})
    @GetMapping(value = "/refreshToken/{applicationId}")
    public ResponseEntity<Map<String, String>> refreshApplicationToken(
        @Parameter(required = true, description = "A valid application Id") @PathVariable("applicationId") String applicationId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        String newApplicationToken = applicationService.refreshApplicationToken(applicationId);
        return PICSUREResponse.success(Map.of("token", newApplicationToken));
    }

    @Operation(description = "DELETE an Application by Id only if the application is not associated by others, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.delete")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(value = "/{applicationId}")
    public ResponseEntity<?> removeById(
        @Parameter(required = true, description = "A valid accessRule Id") @PathVariable("applicationId") final String applicationId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        try {
            List<Application> applications = applicationService.deleteApplicationById(applicationId);
            return PICSUREResponse.success(applications);
        } catch (IllegalArgumentException e) {
            return PICSUREResponse.protocolError(e.getMessage());
        }
    }

}
