package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * <p>Endpoint for registering and administering applications. <br> Note: Only users with the super admin role can access this endpoint.</p>
 */
@Tag(name = "Application Management", description = "Registered client applications and their tokens")
@Controller
@RequestMapping(value = "/application")
public class ApplicationController {

    private final ApplicationService applicationService;

    @Autowired
    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Operation(summary = "Read one application", description = "GET information of one Application with the UUID")
    @ApiResponse(responseCode = "200", description = "The application")
    @ApiResponse(responseCode = "400", description = "No application with that UUID")
    @AuditEvent(type = "OTHER", action = "application.read")
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

        return PICSUREResponse.success(entityById.get());
    }

    @Operation(summary = "List every application", description = "GET a list of existing Applications")
    @ApiResponse(responseCode = "200", description = "Every application")
    @AuditEvent(type = "OTHER", action = "application.list")
    @GetMapping
    public ResponseEntity<List<Application>> getApplicationAll() {
        return PICSUREResponse.success(applicationService.getAllApplications());
    }

    @Operation(summary = "Create applications", description = "POST a list of Applications")
    @ApiResponse(responseCode = "200", description = "The created applications")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Application>> addApplication(
        @Parameter(required = true, description = "A list of AccessRule in JSON format") @RequestBody List<Application> applications,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(applications.size()));
        applications = applicationService.addNewApplications(applications);
        return PICSUREResponse.success(applications);
    }

    @Operation(
        summary = "Update the given fields of applications",
        description = "Update a list of Applications, will only update the fields listed"
    )
    @ApiResponse(responseCode = "200", description = "The updated applications")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<Application>> updateApplication(
        @Parameter(
            required = true, description = "A list of AccessRule with fields to be updated in JSON format"
        ) @RequestBody List<Application> applications, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(applications.size()));
        applications = applicationService.updateApplications(applications);
        return PICSUREResponse.success(applications);
    }

    @Operation(
        summary = "Issue a new token for an application",
        description = "Refresh a token of an application by application Id"
    )
    @ApiResponse(responseCode = "200", description = "The application's new token")
    @ApiResponse(responseCode = "400", description = "No application with that UUID")
    @AuditEvent(type = "ADMIN", action = "application.token_refresh")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @GetMapping(value = "/refreshToken/{applicationId}")
    public ResponseEntity<Map<String, String>> refreshApplicationToken(
        @Parameter(required = true, description = "A valid application Id") @PathVariable("applicationId") String applicationId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        String newApplicationToken = applicationService.refreshApplicationToken(applicationId);
        return PICSUREResponse.success(Map.of("token", newApplicationToken));
    }

    @Operation(
        summary = "Delete an application that nothing references",
        description = "DELETE an Application by Id only if the application is not associated by others"
    )
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The remaining applications"),
            @ApiResponse(responseCode = "400", description = "No application with that UUID"),
            @ApiResponse(responseCode = "409", description = "Other entities still reference this application")}
    )
    @AuditEvent(type = "ADMIN", action = "application.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
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
