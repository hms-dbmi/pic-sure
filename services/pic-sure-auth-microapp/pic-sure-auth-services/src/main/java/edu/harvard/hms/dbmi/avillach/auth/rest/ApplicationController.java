package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.ApplicationUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApplicationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApplicationTokenResponse;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * <p>Endpoint for registering and administering applications. <br> Note: ADMIN and SUPER_ADMIN can read applications. No response carries
 * an application's token except the one that issues a new token, and only SUPER_ADMIN can change an application or issue a token.</p>
 */
@Tag(name = "Application Management", description = "Registered client applications and their tokens.")
@Controller
@RequestMapping(value = "/application")
public class ApplicationController {

    private final ApplicationService applicationService;

    @Autowired
    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Operation(summary = "Read one application", description = "Returns one application by its UUID.")
    @ApiResponse(responseCode = "200", description = "The application, without its token.")
    @ApiResponse(responseCode = "400", description = "No application with that UUID.")
    @AuditEvent(type = "OTHER", action = "application.read")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(value = "/{applicationId}")
    public ResponseEntity<ApplicationResponse> getApplicationById(
        @Parameter(required = true, description = "The UUID of the application to fetch.") @PathVariable(
            "applicationId"
        ) String applicationId
    ) {
        Application application = applicationService.getApplicationByID(applicationId).orElseThrow(
            () -> new PicSureResponseException(
                HttpStatus.BAD_REQUEST, "Invalid request", "Application is not found by given Application ID: " + applicationId
            )
        );

        return PICSUREResponse.success(ApplicationResponse.from(application));
    }

    @Operation(summary = "List every application", description = "Lists every registered application.")
    @ApiResponse(responseCode = "200", description = "Every application, without their tokens.")
    @AuditEvent(type = "OTHER", action = "application.list")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ResponseEntity<List<ApplicationResponse>> getApplicationAll() {
        return PICSUREResponse.success(applicationService.getAllApplications().stream().map(ApplicationResponse::from).toList());
    }

    @Operation(summary = "Create applications", description = "Creates the applications in the request body.")
    @ApiResponse(
        responseCode = "200",
        description = "The created applications with their privileges and without their tokens; issue a token to obtain one."
    )
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<ApplicationResponse>> addApplication(
        @Parameter(
            required = true, description = "The applications to create; the server generates each identifier and token."
        ) @RequestBody List<@NotNull @Valid ApplicationCreateRequest> applications, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(applications.size()));
        return PICSUREResponse
            .success(applicationService.createFrom(applications).stream().map(ApplicationResponse::withPrivileges).toList());
    }

    @Operation(
        summary = "Update the given fields of applications",
        description = "Updates the applications in the request body, changing only the fields each one lists."
    )
    @ApiResponse(responseCode = "200", description = "The updated applications with their privileges and without their tokens.")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @PutMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<List<ApplicationResponse>> updateApplication(
        @Parameter(
            required = true, description = "The applications to update, each named by UUID; a field left out keeps its stored value."
        ) @RequestBody List<@NotNull @Valid ApplicationUpdateRequest> applications, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(applications.size()));
        return PICSUREResponse
            .success(applicationService.updateFrom(applications).stream().map(ApplicationResponse::withPrivileges).toList());
    }

    @Operation(
        summary = "Issue a new token for an application",
        description = "Replaces the application's token with a newly issued one and returns it."
    )
    @ApiResponse(responseCode = "200", description = "The application's new token.")
    @ApiResponse(responseCode = "400", description = "No application with that UUID.")
    @AuditEvent(type = "ADMIN", action = "application.token_refresh")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @GetMapping(value = "/refreshToken/{applicationId}")
    public ResponseEntity<ApplicationTokenResponse> refreshApplicationToken(
        @Parameter(required = true, description = "The uuid of the application whose token is refreshed.") @PathVariable(
            "applicationId"
        ) String applicationId, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        return PICSUREResponse.success(new ApplicationTokenResponse(applicationService.refreshApplicationToken(applicationId)));
    }

    @Operation(
        summary = "Delete an application that nothing references",
        description = "Deletes the application with the given UUID unless other entities still reference it, and returns the remaining applications."
    )
    @ApiResponses(
        {@ApiResponse(responseCode = "200", description = "The remaining applications with their privileges and without their tokens."),
            @ApiResponse(responseCode = "400", description = "No application with that UUID."),
            @ApiResponse(responseCode = "409", description = "Other entities still reference this application.")}
    )
    @AuditEvent(type = "ADMIN", action = "application.delete")
    @PreAuthorize("hasAnyAuthority('SUPER_ADMIN')")
    @DeleteMapping(value = "/{applicationId}")
    public ResponseEntity<List<ApplicationResponse>> removeById(
        @Parameter(required = true, description = "The uuid of the application to delete.") @PathVariable(
            "applicationId"
        ) final String applicationId, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        return PICSUREResponse
            .success(applicationService.deleteApplicationById(applicationId).stream().map(ApplicationResponse::withPrivileges).toList());
    }

}
